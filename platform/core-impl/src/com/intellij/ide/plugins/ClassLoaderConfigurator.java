// Copyright 2000-2020 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.ide.plugins;

import com.intellij.diagnostic.PluginException;
import com.intellij.ide.plugins.cl.PluginAwareClassLoader;
import com.intellij.ide.plugins.cl.PluginClassLoader;
import com.intellij.openapi.components.ServiceDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.lang.UrlClassLoader;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jdom.Element;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Path;
import java.util.*;

@SuppressWarnings({"OptionalUsedAsFieldOrParameterType", "OptionalAssignedToNull"})
@ApiStatus.Internal
final class ClassLoaderConfigurator {
  private static final ClassLoader[] EMPTY_CLASS_LOADER_ARRAY = new ClassLoader[0];
  static final boolean SEPARATE_CLASSLOADER_FOR_SUB = Boolean.parseBoolean(System.getProperty("idea.classloader.per.descriptor", "true"));
  private static final Set<PluginId> SEPARATE_CLASSLOADER_FOR_SUB_ONLY;
  private static final Set<PluginId> SEPARATE_CLASSLOADER_FOR_SUB_EXCLUDE;

  // this list doesn't duplicate of PluginXmlFactory.CLASS_NAMES - interface related must be not here
  private static final @NonNls Set<String> IMPL_CLASS_NAMES = new ReferenceOpenHashSet<>(Arrays.asList(
    "implementation", "implementationClass", "builderClass",
    "serviceImplementation", "class", "className",
    "instance", "implementation-class"));

  // grab classes from platform loader only if nothing is found in any of plugin dependencies
  private final boolean usePluginClassLoader;
  private final ClassLoader coreLoader;
  final Map<PluginId, IdeaPluginDescriptorImpl> idMap;
  private final Map<String, String[]> additionalLayoutMap;

  private Optional<IdeaPluginDescriptorImpl> javaDep;

  // temporary set to produce arrays (avoid allocation for each plugin)
  // set to remove duplicated classloaders
  private final Set<ClassLoader> loaders = new LinkedHashSet<>();
  // temporary list to produce arrays (avoid allocation for each plugin)
  private final List<String> packagePrefixes = new ArrayList<>();

  private final boolean hasAllModules;

  private final UrlClassLoader.Builder urlClassLoaderBuilder;

  static {
    String value = System.getProperty("idea.classloader.per.descriptor.only");
    if (value == null) {
       SEPARATE_CLASSLOADER_FOR_SUB_ONLY = new ReferenceOpenHashSet<>(new PluginId[]{
        PluginId.getId("com.intellij.thymeleaf"),
        PluginId.getId("org.jetbrains.plugins.ruby"),
        PluginId.getId("org.jetbrains.plugins.slim"),
        PluginId.getId("com.intellij.lang.puppet"),
        PluginId.getId("org.jetbrains.plugins.yaml"),
        PluginId.getId("org.jetbrains.plugins.vue"),
        PluginId.getId("org.jetbrains.plugins.go-template"),
        PluginId.getId("com.intellij.kubernetes"),
        PluginId.getId("JavaScript"),
        PluginId.getId("com.jetbrains.space"),
        PluginId.getId("org.jetbrains.plugins.github")
      });
    }
    else if (value.isEmpty()) {
      SEPARATE_CLASSLOADER_FOR_SUB_ONLY = Collections.emptySet();
    }
    else {
      SEPARATE_CLASSLOADER_FOR_SUB_ONLY = new ReferenceOpenHashSet<>();
      for (String id : value.split(",")) {
        SEPARATE_CLASSLOADER_FOR_SUB_ONLY.add(PluginId.getId(id));
      }
    }

    SEPARATE_CLASSLOADER_FOR_SUB_EXCLUDE = new ReferenceOpenHashSet<>(new PluginId[]{
      PluginId.getId("org.jetbrains.kotlin")
    });
  }

  ClassLoaderConfigurator(boolean usePluginClassLoader,
                          @NotNull ClassLoader coreLoader,
                          @NotNull Map<PluginId, IdeaPluginDescriptorImpl> idMap,
                          @NotNull Map<String, String[]> additionalLayoutMap) {
    this.usePluginClassLoader = usePluginClassLoader;
    this.coreLoader = coreLoader;
    this.idMap = idMap;
    this.additionalLayoutMap = additionalLayoutMap;

    hasAllModules = idMap.containsKey(PluginManagerCore.ALL_MODULES_MARKER);
    urlClassLoaderBuilder = UrlClassLoader.build().allowLock().useCache().urlsInterned();
  }

  @SuppressWarnings("RedundantSuppression")
  private static @NotNull Logger getLogger() {
    // do not use class reference here
    //noinspection SSBasedInspection
    return Logger.getInstance("#com.intellij.ide.plugins.PluginManager");
  }

  void configureDependenciesIfNeeded(@NotNull Map<IdeaPluginDescriptorImpl, @NotNull List<IdeaPluginDescriptorImpl>> mainToSub, @NotNull IdeaPluginDescriptorImpl dependencyPlugin) {
    for (Map.Entry<IdeaPluginDescriptorImpl, @NotNull List<IdeaPluginDescriptorImpl>> entry : mainToSub.entrySet()) {
      IdeaPluginDescriptorImpl mainDependent = entry.getKey();
      PluginClassLoader mainDependentClassLoader = (PluginClassLoader)Objects.requireNonNull(mainDependent.getClassLoader());

      if (isClassloaderPerDescriptorEnabled(mainDependent)) {
        for (PluginDependency dependency : Objects.requireNonNull(mainDependent.pluginDependencies)) {
          urlClassLoaderBuilder.urls(mainDependentClassLoader.getUrls());
          for (IdeaPluginDescriptorImpl subDescriptor : entry.getValue()) {
            if (subDescriptor == dependency.subDescriptor) {
              configureSubPlugin(dependency, mainDependentClassLoader);
              break;
            }
          }
        }
      }
      else {
        mainDependentClassLoader.attachParent(Objects.requireNonNull(dependencyPlugin.getClassLoader()));
        for (IdeaPluginDescriptorImpl subDescriptor : entry.getValue()) {
          subDescriptor.setClassLoader(mainDependentClassLoader);
        }
      }
    }

    loaders.clear();
    urlClassLoaderBuilder.urls(Collections.emptyList());
  }

  void configure(@NotNull IdeaPluginDescriptorImpl mainDependent) {
    if (mainDependent.getPluginId() == PluginManagerCore.CORE_ID || mainDependent.isUseCoreClassLoader()) {
      setPluginClassLoaderForMainAndSubPlugins(mainDependent, coreLoader);
      return;
    }
    else if (!usePluginClassLoader) {
      setPluginClassLoaderForMainAndSubPlugins(mainDependent, null);
    }

    loaders.clear();

    // first, set class loader for main descriptor
    if (hasAllModules) {
      IdeaPluginDescriptorImpl implicitDependency = PluginManagerCore.getImplicitDependency(mainDependent, () -> {
        // first, set class loader for main descriptor
        if (javaDep == null) {
          javaDep = Optional.ofNullable(idMap.get(PluginManagerCore.JAVA_PLUGIN_ID));
        }
        return javaDep.orElse(null);
      });

      if (implicitDependency != null) {
        addLoaderOrLogError(mainDependent, implicitDependency, loaders);
      }
    }

    List<Path> classPath = mainDependent.jarFiles;
    if (classPath == null) {
      classPath = mainDependent.collectClassPath(additionalLayoutMap);
    }
    else {
      mainDependent.jarFiles = null;
    }

    List<URL> urls = new ArrayList<>(classPath.size());
    for (Path pathElement : classPath) {
      urls.add(localFileToUrl(pathElement, mainDependent));
    }

    urlClassLoaderBuilder.urls(urls);

    List<PluginDependency> pluginDependencies = mainDependent.pluginDependencies;
    if (pluginDependencies == null) {
      assert !mainDependent.isUseIdeaClassLoader();
      mainDependent.setClassLoader(new PluginClassLoader(urlClassLoaderBuilder, loaders.toArray(EMPTY_CLASS_LOADER_ARRAY), mainDependent, mainDependent.getPluginPath(), coreLoader));
      return;
    }

    // no need to process dependencies recursively because dependency will use own classloader
    // (that in turn will delegate class searching to parent class loader if needed)
    for (PluginDependency dependency : pluginDependencies) {
      if (dependency.isDisabledOrBroken || (isClassloaderPerDescriptorEnabled(mainDependent) && dependency.subDescriptor != null)) {
        continue;
      }

      IdeaPluginDescriptorImpl dependencyDescriptor = idMap.get(dependency.id);
      if (dependencyDescriptor != null) {
        ClassLoader loader = dependencyDescriptor.getClassLoader();
        if (loader == null) {
          getLogger().error(PluginLoadingError.formatErrorMessage(mainDependent,
                                                                  "requires missing class loader for '" + dependencyDescriptor.getName() + "'"));
        }
        else if (loader != coreLoader) {
          loaders.add(loader);
        }
      }
    }

    ClassLoader mainDependentClassLoader;
    if (mainDependent.isUseIdeaClassLoader()) {
      mainDependentClassLoader = configureUsingIdeaClassloader(classPath, mainDependent);
    }
    else {
      ClassLoader[] parentLoaders;
      if (loaders.isEmpty()) {
        parentLoaders = usePluginClassLoader ? EMPTY_CLASS_LOADER_ARRAY : new ClassLoader[]{coreLoader};
      }
      else {
        parentLoaders = loaders.toArray(EMPTY_CLASS_LOADER_ARRAY);
      }
      mainDependentClassLoader = new PluginClassLoader(urlClassLoaderBuilder, parentLoaders, mainDependent, mainDependent.getPluginPath(), usePluginClassLoader ? coreLoader : null);
    }

    // second, set class loaders for sub descriptors
    if (usePluginClassLoader && isClassloaderPerDescriptorEnabled(mainDependent)) {
      mainDependent.setClassLoader(mainDependentClassLoader);
      for (PluginDependency dependencyInfo : pluginDependencies) {
        configureSubPlugin(dependencyInfo, mainDependentClassLoader);
      }
    }
    else {
      setPluginClassLoaderForMainAndSubPlugins(mainDependent, mainDependentClassLoader);
    }

    // reset to ensure that stalled data will be not reused somehow later
    loaders.clear();
    urlClassLoaderBuilder.urls(Collections.emptyList());
  }

  private static boolean isClassloaderPerDescriptorEnabled(@NotNull IdeaPluginDescriptorImpl mainDependent) {
    if (!SEPARATE_CLASSLOADER_FOR_SUB || SEPARATE_CLASSLOADER_FOR_SUB_EXCLUDE.contains(mainDependent.getPluginId())) {
      return false;
    }
    return SEPARATE_CLASSLOADER_FOR_SUB_ONLY.isEmpty() || SEPARATE_CLASSLOADER_FOR_SUB_ONLY.contains(mainDependent.getPluginId());
  }

  private void configureSubPlugin(@NotNull PluginDependency dependencyInfo, @NotNull ClassLoader mainDependentClassLoader) {
    IdeaPluginDescriptorImpl dependent = dependencyInfo.isDisabledOrBroken ? null : dependencyInfo.subDescriptor;
    if (dependent == null) {
      return;
    }

    assert !dependent.isUseIdeaClassLoader();
    IdeaPluginDescriptorImpl dependency = idMap.get(dependencyInfo.id);
    if (dependency == null || !dependency.isEnabled()) {
      return;
    }

    packagePrefixes.clear();
    collectPackagePrefixes(dependent, packagePrefixes);
    if (packagePrefixes.isEmpty()) {
      getLogger().error("Optional descriptor " + dependencyInfo + " doesn't define extra classes");
    }

    loaders.clear();
    // add main descriptor classloader as parent
    loaders.add(mainDependentClassLoader);
    addLoaderOrLogError(dependent, dependency, loaders);

    SubPluginClassLoader subClassloader = new SubPluginClassLoader(dependent,
                                                                   urlClassLoaderBuilder,
                                                                   loaders.toArray(EMPTY_CLASS_LOADER_ARRAY),
                                                                   packagePrefixes.toArray(ArrayUtilRt.EMPTY_STRING_ARRAY),
                                                                   coreLoader);
    dependent.setClassLoader(subClassloader);

    if (dependent.pluginDependencies != null) {
      for (PluginDependency dependencyInfo1 : dependent.pluginDependencies) {
        configureSubPlugin(dependencyInfo1, subClassloader);
      }
    }
  }

  private static void collectPackagePrefixes(@NotNull IdeaPluginDescriptorImpl dependent, @NotNull List<String> packagePrefixes) {
    // from extensions
    dependent.getUnsortedEpNameToExtensionElements().values().forEach(elements -> {
      for (Element element : elements) {
        if (!element.hasAttributes()) {
          continue;
        }

        for (String attributeName : IMPL_CLASS_NAMES) {
          String className = element.getAttributeValue(attributeName);
          if (className != null && !className.isEmpty()) {
            addPackageByClassNameIfNeeded(className, packagePrefixes);
            break;
          }
        }
      }
    });

    // from services
    collectFromServices(dependent.appContainerDescriptor, packagePrefixes);
    collectFromServices(dependent.projectContainerDescriptor, packagePrefixes);
    collectFromServices(dependent.moduleContainerDescriptor, packagePrefixes);
  }

  private static void addPackageByClassNameIfNeeded(@NotNull String name, @NotNull List<String> packagePrefixes) {
    for (String packagePrefix : packagePrefixes) {
      if (name.startsWith(packagePrefix)) {
        return;
      }
    }

    // for classes like com.intellij.thymeleaf.lang.ThymeleafParserDefinition$SPRING_SECURITY_EXPRESSIONS
    // we must not try to load the containing package
    if (name.indexOf('$') != -1) {
      packagePrefixes.add(name);
      return;
    }

    int lastPackageDot = name.lastIndexOf('.');
    if (lastPackageDot > 0 && lastPackageDot != name.length()) {
      addPackagePrefixIfNeeded(packagePrefixes, name.substring(0, lastPackageDot + 1));
    }
  }

  private static void addPackagePrefixIfNeeded(@NotNull List<String> packagePrefixes, @NotNull String packagePrefix) {
    for (int i = 0; i < packagePrefixes.size(); i++) {
      String existingPackagePrefix = packagePrefixes.get(i);
      if (packagePrefix.startsWith(existingPackagePrefix)) {
        return;
      }
      else if (existingPackagePrefix.startsWith(packagePrefix) && existingPackagePrefix.indexOf('$') == -1) {
        packagePrefixes.set(i, packagePrefix);
        for (int j = packagePrefixes.size() - 1; j > i; j--) {
          existingPackagePrefix = packagePrefixes.get(i);
          if (existingPackagePrefix.startsWith(packagePrefix)) {
            packagePrefixes.remove(j);
          }
        }
        return;
      }
    }

    packagePrefixes.add(packagePrefix);
  }

  private static void collectFromServices(@NotNull ContainerDescriptor containerDescriptor, @NotNull List<String> packagePrefixes) {
    List<ServiceDescriptor> services = containerDescriptor.services;
    if (services == null) {
      return;
    }

    for (ServiceDescriptor service : services) {
      // testServiceImplementation is ignored by intention
      if (service.serviceImplementation != null) {
        addPackageByClassNameIfNeeded(service.serviceImplementation, packagePrefixes);
      }
      if (service.headlessImplementation != null) {
        addPackageByClassNameIfNeeded(service.headlessImplementation, packagePrefixes);
      }
    }
  }

  private @NotNull static ClassLoader configureUsingIdeaClassloader(@NotNull List<Path> classPath, @NotNull IdeaPluginDescriptorImpl descriptor) {
    getLogger().warn(descriptor.getPluginId() + " uses deprecated `use-idea-classloader` attribute");
    ClassLoader loader = ClassLoaderConfigurator.class.getClassLoader();
    try {
      Class<?> loaderClass = loader.getClass();
      if (loaderClass.getName().endsWith(".BootstrapClassLoaderUtil$TransformingLoader")) {
        loaderClass = loaderClass.getSuperclass();
      }

      // `UrlClassLoader#addURL` can't be invoked directly, because the core classloader is created at bootstrap in a "lost" branch
      MethodHandle addURL = MethodHandles.lookup().findVirtual(loaderClass, "addURL", MethodType.methodType(void.class, URL.class));
      for (Path pathElement : classPath) {
        addURL.invoke(loader, localFileToUrl(pathElement, descriptor));
      }
      return loader;
    }
    catch (Throwable t) {
      throw new IllegalStateException("An unexpected core classloader: " + loader.getClass(), t);
    }
  }

  private void addLoaderOrLogError(@NotNull IdeaPluginDescriptorImpl dependent,
                                   @NotNull IdeaPluginDescriptorImpl dependency,
                                   @NotNull Collection<ClassLoader> loaders) {
    ClassLoader loader = dependency.getClassLoader();
    if (loader == null) {
      getLogger().error(PluginLoadingError.formatErrorMessage(dependent,
                                                              "requires missing class loader for '" + dependency.getName() + "'"));
    }
    else if (loader != coreLoader) {
      loaders.add(loader);
    }
  }

  private void setPluginClassLoaderForMainAndSubPlugins(@NotNull IdeaPluginDescriptorImpl rootDescriptor, @Nullable ClassLoader classLoader) {
    rootDescriptor.setClassLoader(classLoader);
    for (PluginDependency dependency : rootDescriptor.getPluginDependencies()) {
      if (dependency.subDescriptor != null) {
        IdeaPluginDescriptorImpl descriptor = idMap.get(dependency.id);
        if (descriptor != null && descriptor.isEnabled()) {
          setPluginClassLoaderForMainAndSubPlugins(dependency.subDescriptor, classLoader);
        }
      }
    }
  }

  private static @NotNull URL localFileToUrl(@NotNull Path file, @NotNull IdeaPluginDescriptor descriptor) {
    try {
      // it is important not to have traversal elements in classpath
      return file.normalize().toUri().toURL();
    }
    catch (MalformedURLException e) {
      throw new PluginException("Corrupted path element: `" + file + '`', e, descriptor.getPluginId());
    }
  }

  @ApiStatus.Internal
  public static final class SubPluginClassLoader extends PluginClassLoader implements PluginAwareClassLoader.SubClassLoader {
    private final String[] packagePrefixes;

    SubPluginClassLoader(@NotNull IdeaPluginDescriptorImpl pluginDescriptor,
                         @NotNull Builder urlClassLoaderBuilder,
                         @NotNull ClassLoader @NotNull [] parents,
                         @NotNull String @NotNull[] packagePrefixes,
                         @NotNull ClassLoader coreLoader) {
      super(urlClassLoaderBuilder, parents, pluginDescriptor, pluginDescriptor.getPluginPath(), coreLoader);

      this.packagePrefixes = packagePrefixes;
    }

    @Override
    protected @Nullable Class<?> loadClassInsideSelf(@NotNull String name, boolean force) {
      if (force) {
        return super.loadClassInsideSelf(name, true);
      }

      for (String packagePrefix : packagePrefixes) {
        if (name.startsWith(packagePrefix)) {
          return super.loadClassInsideSelf(name, true);
        }
      }

      int subIndex = name.indexOf('$');
      if (subIndex > 0) {
        // load inner classes
        // we check findLoadedClass because classNames doesn't have full set of suitable names - PluginAwareClassLoader.SubClassLoader is used to force loading classes from sub classloader
        Class<?> loadedClass = findLoadedClass(name.substring(0, subIndex));
        if (loadedClass != null && loadedClass.getClassLoader() == this) {
          return super.loadClassInsideSelf(name, true);
        }
      }

      return null;
    }
  }
}
