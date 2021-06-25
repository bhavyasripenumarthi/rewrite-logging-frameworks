/*
 * Copyright 2021 the original author or authors.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * https://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openrewrite.java.logging.logback;

import org.openrewrite.ExecutionContext;
import org.openrewrite.Recipe;
import org.openrewrite.TreeVisitor;
import org.openrewrite.internal.ListUtils;
import org.openrewrite.java.*;
import org.openrewrite.java.search.UsesType;
import org.openrewrite.java.tree.J;
import org.openrewrite.java.tree.JavaType;
import org.openrewrite.java.tree.TypeUtils;

/**
 * {@link Log4jAppenderToLogbackVisitor} operates on the following assumptions:
 * <ul>
 *     <li>The contents of the append() method remains unchanged.</li>
 *     <li>The requiresLayout() method is not used in logback and can be removed.</li>
 *     <li>In logback, the stop() method is the equivalent of log4j's close() method.</li>
 * </ul>
 *
 * @see <a href="http://logback.qos.ch/manual/migrationFromLog4j.html">Migration from log4j</a>
 */
public class Log4jAppenderToLogback extends Recipe {
    @Override
    public String getDisplayName() {
        return "Migrate from Log4j appender";
    }

    @Override
    public String getDescription() {
        return "Migrates custom Log4j appender components to `logback-classic`.";
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getSingleSourceApplicableTest() {
        return new UsesType<>("org.apache.log4j.AppenderSkeleton");
    }

    @Override
    protected TreeVisitor<?, ExecutionContext> getVisitor() {
        return new Log4jAppenderToLogbackVisitor();
    }

    public static class Log4jAppenderToLogbackVisitor extends JavaIsoVisitor<ExecutionContext> {
        @Override
        public J.CompilationUnit visitCompilationUnit(J.CompilationUnit cu, ExecutionContext ctx) {
            doAfterVisit(new ChangeMethodName("org.apache.log4j.Layout format(..)", "doLayout"));
            return super.visitCompilationUnit(cu, ctx);
        }

        @Override
        public J.ClassDeclaration visitClassDeclaration(J.ClassDeclaration classDecl, ExecutionContext ctx) {
            J.ClassDeclaration cd = super.visitClassDeclaration(classDecl, ctx);

            if (cd.getExtends() != null && cd.getExtends().getType() != null) {
                JavaType.FullyQualified fullyQualifiedExtends = TypeUtils.asFullyQualified(cd.getExtends().getType());
                if (fullyQualifiedExtends != null && "org.apache.log4j.AppenderSkeleton".equals(fullyQualifiedExtends.getFullyQualifiedName())) {

                    maybeRemoveImport("org.apache.log4j.AppenderSkeleton");
                    maybeAddImport("ch.qos.logback.core.AppenderBase");
                    maybeAddImport("ch.qos.logback.classic.spi.ILoggingEvent");

                    doAfterVisit(new ChangeType("org.apache.log4j.spi.LoggingEvent", "ch.qos.logback.classic.spi.ILoggingEvent"));
                    doAfterVisit(new ChangeType("org.apache.log4j.Layout", "ch.qos.logback.core.LayoutBase"));

                    cd = cd.withTemplate(
                            JavaTemplate.builder(this::getCursor, "AppenderBase<ILoggingEvent>")
                                    .imports("ch.qos.logback.core.AppenderBase", "ch.qos.logback.classic.spi.ILoggingEvent")
                                    .build(),
                            cd.getCoordinates().replaceExtendsClause()
                    );

                    // should be covered by maybeAddImport, fixme
                    doAfterVisit(new AddImport<>("ch.qos.logback.core.AppenderBase", null, false));
                }

                cd = cd.withBody(cd.getBody().withStatements(ListUtils.map(cd.getBody().getStatements(), statement -> {
                    if (statement instanceof J.MethodDeclaration) {
                        J.MethodDeclaration method = (J.MethodDeclaration) statement;
                        if ("requiresLayout".equals(method.getSimpleName())) {
                            return null;
                        } else if ("close".equals(method.getSimpleName())) {
                            if (method.getBody() != null && method.getBody().getStatements().isEmpty()) {
                                return null;
                            }

                            return method.withName(method.getName().withName("stop"));
                        }
                    }
                    return statement;
                })));

            }

            return cd;
        }

    }

}
