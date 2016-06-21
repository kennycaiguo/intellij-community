/*
 * Copyright 2011 Bas Leijdekkers
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.siyeh.ig.style;

import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiExpression;
import com.intellij.psi.PsiLambdaExpression;
import com.intellij.refactoring.util.RefactoringUtil;
import com.siyeh.InspectionGadgetsBundle;
import com.siyeh.ig.BaseInspection;
import com.siyeh.ig.BaseInspectionVisitor;
import com.siyeh.ig.InspectionGadgetsFix;
import org.jetbrains.annotations.Nls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class ExpandOneLineLambda2CodeBlockInspection extends BaseInspection {
  private static final Logger LOG = Logger.getInstance("#" + ExpandOneLineLambda2CodeBlockInspection.class.getName());

  @Nls
  @NotNull
  @Override
  public String getDisplayName() {
    return InspectionGadgetsBundle.message("expand.one.line.lambda2.code.block.descriptor");
  }

  @NotNull
  @Override
  protected String buildErrorString(Object... infos) {
    return InspectionGadgetsBundle.message("expand.one.line.lambda2.code.block.name");
  }

  @Override
  public BaseInspectionVisitor buildVisitor() {
    return new OneLineLambda2CodeBlockVisitor();
  }

  @Nullable
  @Override
  protected InspectionGadgetsFix buildFix(Object... infos) {
    return new OneLineLambda2CodeBlockFix();
  }

  private static class OneLineLambda2CodeBlockVisitor extends BaseInspectionVisitor {
    @Override
    public void visitLambdaExpression(PsiLambdaExpression lambdaExpression) {
      super.visitLambdaExpression(lambdaExpression);
      if (lambdaExpression.getBody() instanceof PsiExpression) {
        registerError(lambdaExpression);
      }
    }
  }

  private static class OneLineLambda2CodeBlockFix extends InspectionGadgetsFix {
    @Override
    protected void doFix(Project project, ProblemDescriptor descriptor) {
      final PsiElement element = descriptor.getPsiElement();
      if (element instanceof PsiLambdaExpression) {
        final PsiElement body = ((PsiLambdaExpression)element).getBody();
        if (body instanceof PsiExpression) {
          RefactoringUtil.expandExpressionLambdaToCodeBlock(body);
        }
      }
    }

    @Nls
    @NotNull
    @Override
    public String getName() {
      return InspectionGadgetsBundle.message("expand.one.line.lambda2.code.block.quickfix");
    }

    @Nls
    @NotNull
    @Override
    public String getFamilyName() {
      return getName();
    }
  }
}
