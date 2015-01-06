/*
 * Copyright (C) 2014 Google, Inc.
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
package com.google.auto.value.processor;

import com.google.auto.common.MoreElements;
import com.google.auto.common.MoreTypes;
import com.google.auto.value.AutoValue;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import javax.annotation.CheckReturnValue;
import javax.annotation.processing.ProcessingEnvironment;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.lang.model.util.ElementFilter;
import javax.lang.model.util.Elements;
import javax.lang.model.util.Types;

/**
 * Support for AutoValue builders.
 *
 * @author Éamonn McManus
 */
class BuilderSpec {
  private final TypeElement autoValueClass;
  private final Types typeUtils;
  private final Elements elementUtils;
  private final ErrorReporter errorReporter;

  BuilderSpec(
      TypeElement autoValueClass,
      ProcessingEnvironment processingEnv,
      ErrorReporter errorReporter) {
    this.autoValueClass = autoValueClass;
    this.typeUtils = processingEnv.getTypeUtils();
    this.elementUtils = processingEnv.getElementUtils();
    this.errorReporter = errorReporter;
  }

  private static final Set<ElementKind> CLASS_OR_INTERFACE =
      Sets.immutableEnumSet(ElementKind.CLASS, ElementKind.INTERFACE);

  /**
   * Determines if the {@code @AutoValue} class for this instance has a correct nested
   * {@code @AutoValue.Builder} class or interface and return a representation of it in an
   * {@code Optional} if so.
   */
  Optional<Builder> getBuilder() {
    Optional<TypeElement> builderTypeElement = Optional.absent();
    for (TypeElement containedClass : ElementFilter.typesIn(autoValueClass.getEnclosedElements())) {
      if (MoreElements.isAnnotationPresent(containedClass, AutoValue.Builder.class)) {
        if (!CLASS_OR_INTERFACE.contains(containedClass.getKind())) {
          errorReporter.reportError(
              "@AutoValue.Builder can only apply to a class or an interface", containedClass);
        } else if (builderTypeElement.isPresent()) {
          errorReporter.reportError(
              autoValueClass + " already has a Builder: " + builderTypeElement.get(),
              containedClass);
        } else {
          builderTypeElement = Optional.of(containedClass);
        }
      }
    }

    if (builderTypeElement.isPresent()) {
      return builderFrom(builderTypeElement.get());
    } else {
      return Optional.absent();
    }
  }

  /**
   * Representation of an {@code AutoValue.Builder} class or interface.
   */
  class Builder {
    private final TypeElement builderTypeElement;
    private final ExecutableElement buildMethod;
    private final ImmutableList<ExecutableElement> setters;

    Builder(
        TypeElement builderTypeElement,
        ExecutableElement build,
        List<ExecutableElement> setters) {
      this.builderTypeElement = builderTypeElement;
      this.buildMethod = build;
      this.setters = ImmutableList.copyOf(setters);
    }

    ExecutableElement buildMethod() {
      return buildMethod;
    }

    /**
     * Returns true if the setters in this builder correspond exactly to the given getter methods.
     * Otherwise, emits error messages and returns false.
     *
     * @param getterToPropertyName a list of getter methods, such as {@code abstract String foo();}
     * or {@code abstract String getFoo();}.
     */
    @CheckReturnValue
    boolean validSetters(Map<ExecutableElement, String> getterToPropertyName) {
      // Map property names to types based on the getters.
      Map<String, TypeMirror> getterMap = new TreeMap<String, TypeMirror>();
      for (Map.Entry<ExecutableElement, String> entry : getterToPropertyName.entrySet()) {
        getterMap.put(entry.getValue(), entry.getKey().getReturnType());
      }

      boolean ok = true;

      // For each setter, check that its name and type correspond to a getter, and remove it from
      // the map if so.
      for (ExecutableElement setter : setters) {
        String name = setter.getSimpleName().toString();
        TypeMirror type = getterMap.get(name);
        if (type == null) {
          errorReporter.reportError(
              "Method does not correspond to a property of " + autoValueClass, setter);
          ok = false;
        } else {
          VariableElement parameter = Iterables.getOnlyElement(setter.getParameters());
          if (typeUtils.isSameType(type, parameter.asType())) {
            getterMap.remove(name);
          } else {
            errorReporter.reportError("Parameter type should be " + type, parameter);
            ok = false;
          }
        }
      }
      if (!ok) {
        return false;
      }

      // If there are any properties left in the map then we didn't see setters for them. Report
      // an error for each one separately.
      if (getterMap.isEmpty()) {
        return true;
      }
      for (Map.Entry<String, TypeMirror> entry : getterMap.entrySet()) {
        String error = String.format(
            "Expected a method with this signature: %s %s(%s)",
            builderTypeElement, entry.getKey(), entry.getValue());
        errorReporter.reportError(error, builderTypeElement);
      }
      return false;
    }

    void defineVars(AutoValueTemplateVars vars) {
      vars.builderIsInterface = builderTypeElement.getKind() == ElementKind.INTERFACE;
      vars.builderTypeName = TypeSimplifier.classNameOf(builderTypeElement);
      vars.buildMethodName = buildMethod.getSimpleName().toString();
    }
  }

  /**
   * Returns a representation of the given {@code @AutoValue.Builder} class or interface. If the
   * class or interface has abstract methods that could not be part of any builder, emits error
   * messages and returns null.
   */
  private Optional<Builder> builderFrom(TypeElement builderTypeElement) {
    List<ExecutableElement> buildMethods = new ArrayList<ExecutableElement>();
    List<ExecutableElement> setterMethods = new ArrayList<ExecutableElement>();
    boolean ok = true;
    // For each abstract method (in builderTypeElement or inherited), check that it is either
    // a setter method or a build method. A setter method has one argument and returns
    // builderTypeElement. A build method has no arguments and returns the @AutoValue class.
    // Record each method in one of the two lists.
    for (ExecutableElement method : abstractMethods(builderTypeElement)) {
      boolean thisOk = false;
      int nParameters = method.getParameters().size();
      if (nParameters == 0
          && typeUtils.isSameType(method.getReturnType(), autoValueClass.asType())) {
        buildMethods.add(method);
        thisOk = true;
      } else if (nParameters == 1
          && typeUtils.isSameType(method.getReturnType(), builderTypeElement.asType())) {
        setterMethods.add(method);
        thisOk = true;
      }
      if (!thisOk) {
        errorReporter.reportError(
            "Builder methods must either have no arguments and return " + autoValueClass
            + " or have one argument and return " + builderTypeElement, method);
        ok = false;
      }
    }
    if (buildMethods.isEmpty()) {
      errorReporter.reportError(
          "Builder must have a single no-argument method returning " + autoValueClass,
          builderTypeElement);
      ok = false;
    } else if (buildMethods.size() > 1) {
      // More than one eligible build method. Emit an error for each one, that is attached to
      // that build method.
      for (ExecutableElement buildMethod : buildMethods) {
        errorReporter.reportError(
            "Builder must have a single no-argument method returning " + autoValueClass,
            buildMethod);
      }
      ok = false;
    }
    if (ok) {
      return Optional.of(
          new Builder(builderTypeElement, Iterables.getOnlyElement(buildMethods), setterMethods));
    } else {
      return Optional.absent();
    }
  }

  // Return a list of all abstract methods in the given TypeElement or inherited from ancestors.
  private List<ExecutableElement> abstractMethods(TypeElement typeElement) {
    List<ExecutableElement> methods = new ArrayList<ExecutableElement>();
    addAbstractMethods(typeElement.asType(), methods);
    return methods;
  }

  private void addAbstractMethods(
      TypeMirror typeMirror, List<ExecutableElement> abstractMethods) {
    if (typeMirror.getKind() != TypeKind.DECLARED) {
      return;
    }

    TypeElement typeElement = MoreTypes.asTypeElement(typeUtils, typeMirror);
    addAbstractMethods(typeElement.getSuperclass(), abstractMethods);
    for (TypeMirror interfaceMirror : typeElement.getInterfaces()) {
      addAbstractMethods(interfaceMirror, abstractMethods);
    }
    for (ExecutableElement method : ElementFilter.methodsIn(typeElement.getEnclosedElements())) {
      for (Iterator<ExecutableElement> it = abstractMethods.iterator(); it.hasNext(); ) {
        ExecutableElement maybeOverridden = it.next();
        if (elementUtils.overrides(method, maybeOverridden, typeElement)) {
          it.remove();
        }
      }
      if (method.getModifiers().contains(Modifier.ABSTRACT)) {
        abstractMethods.add(method);
      }
    }
  }
}
