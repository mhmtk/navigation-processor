package com.mhmt.navigationprocessor.processor;

import com.mhmt.navigationprocessor.processor.error.IncompatibleModifierError;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeSpec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Set;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.RoundEnvironment;
import javax.annotation.processing.SupportedAnnotationTypes;
import javax.annotation.processing.SupportedSourceVersion;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.ArrayType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;

@SupportedAnnotationTypes("com.mhmt.navigationprocessor.processor.Required")
@SupportedSourceVersion(SourceVersion.RELEASE_7)
public class NavigationAnnotationProcessor extends AbstractProcessor {

  private static final ClassName intentClass = ClassName.get("android.content", "Intent");
  private static final ClassName contextClass = ClassName.get("android.content", "Context");

  @Override public boolean process(final Set<? extends TypeElement> annotations, final RoundEnvironment roundEnv) {

    HashMap<Element, List<Element>> classToFieldListMap = populateClassToFieldListMap(roundEnv);

    TypeSpec.Builder navigatorClassBuilder = TypeSpec.classBuilder("Navigator")
            .addModifiers(Modifier.PUBLIC, Modifier.FINAL);

    // Go through the activities
    MethodSpec.Builder startMethodBuilder;
    MethodSpec.Builder bindMethodBuilder;
    for (Element clazz : classToFieldListMap.keySet()) {
      startMethodBuilder = MethodSpec.methodBuilder("start".concat(clazz.getSimpleName().toString()))
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(void.class)
              .addParameter(contextClass, "context", Modifier.FINAL)
              .addStatement("$T intent = new $T(context, $T.class)", intentClass, intentClass, clazz.asType());
      bindMethodBuilder = MethodSpec.methodBuilder("bind")
              .addModifiers(Modifier.PUBLIC, Modifier.STATIC)
              .returns(void.class)
              .addParameter(ParameterizedTypeName.get(clazz.asType()), "activity", Modifier.FINAL);

      for (Element annotatedField : classToFieldListMap.get(clazz)) {
        startMethodBuilder
                .addParameter(ParameterizedTypeName.get(annotatedField.asType()), annotatedField.getSimpleName().toString(), Modifier.FINAL)
                .addStatement("intent.putExtra($S, $L)", annotatedField.getSimpleName(), annotatedField.getSimpleName());
        if (annotatedField.getAnnotation(Required.class).bind()) {
          if (!annotatedField.getModifiers().contains(Modifier.PUBLIC)) {
            throw new IncompatibleModifierError(clazz.getSimpleName().toString(), annotatedField.getSimpleName().toString());
          }
          addBindStatement(bindMethodBuilder, annotatedField);
        }
      }
      startMethodBuilder.addStatement("context.startActivity(intent)");
      navigatorClassBuilder.addMethod(startMethodBuilder.build());
      navigatorClassBuilder.addMethod(bindMethodBuilder.build());
    }

    JavaFile javaFile = JavaFile.builder("com.mhmt.navigationprocessor.generated", navigatorClassBuilder.build())
            .build();
    try {
      javaFile.writeTo(processingEnv.getFiler());
    } catch (IOException e) {
      e.printStackTrace();
    }

    return true;
  }

  private void addBindStatement(MethodSpec.Builder bindMethodBuilder, Element field) {
    if (ofClass(field, Double.class) || ofClass(field, double.class)
            || ofClass(field, Long.class) || ofClass(field, long.class)
            || ofClass(field, Float.class) || ofClass(field, float.class)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().get$LExtra($S, -1)",
              field.getSimpleName(),
              capitalizeFirstLetter(getClassNameAsString(field)),
              field.getSimpleName());
    } else if (ofClass(field, Byte.class) || ofClass(field, byte.class)
            || ofClass(field, Short.class) || ofClass(field, short.class)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().get$LExtra($S, ($L) -1)",
              field.getSimpleName(),
              capitalizeFirstLetter(getClassNameAsString(field)),
              field.getSimpleName(),
              lowerCaseFirstLetter(getClassNameAsString(field)));
    } else if (ofClass(field, char.class)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().get$LExtra($S, 'm')",
              field.getSimpleName(),
              capitalizeFirstLetter(getClassNameAsString(field)),
              field.getSimpleName());
    } else if (ofClass(field, Integer.class) || ofClass(field, int.class)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().getIntExtra($S, -1)",
              field.getSimpleName(),
              field.getSimpleName());
    } else if (ofClass(field, Boolean.class) || ofClass(field, boolean.class)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().getBooleanExtra($S, false)",
              field.getSimpleName(),
              field.getSimpleName());
    } else if (isArray(field)) {
      if (isParcelableArray(field)) {
        bindMethodBuilder.addStatement("activity.$L = ($T) activity.getIntent().getParcelableArrayExtra($S)",
                field.getSimpleName(),
                ParameterizedTypeName.get(field.asType()),
                field.getSimpleName());
      } else {
        bindMethodBuilder.addStatement("activity.$L = activity.getIntent().get$LExtra($S)",
                field.getSimpleName(),
                capitalizeFirstLetter(getClassNameAsString(field)),
                field.getSimpleName());
      }
    } else if (ofClass(field, String.class) || isBundle(field) || ofClass(field, CharSequence.class)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().get$LExtra($S)",
              field.getSimpleName(),
              capitalizeFirstLetter(getClassNameAsString(field)),
              field.getSimpleName());
    } else if (isParcelable(field)) {
      bindMethodBuilder.addStatement("activity.$L = activity.getIntent().getParcelableExtra($S)",
              field.getSimpleName(),
              field.getSimpleName());
    } else if (isSerializable(field)) {
      bindMethodBuilder.addStatement("activity.$L = ($T) activity.getIntent().getSerializableExtra($S)",
              field.getSimpleName(),
              ParameterizedTypeName.get(field.asType()),
              field.getSimpleName());
    }
  }

  private HashMap<Element, List<Element>> populateClassToFieldListMap(final RoundEnvironment roundEnv) {
    HashMap<Element, List<Element>> classToVariableListMap = new HashMap<>();
    Element clazz;
    ArrayList<Element> elementList;
    for (Element rootElement : roundEnv.getElementsAnnotatedWith(Required.class)) {
      clazz = rootElement.getEnclosingElement();
      if (!classToVariableListMap.containsKey(clazz)) {
        elementList = new ArrayList<>();
        elementList.add(rootElement);
        classToVariableListMap.put(clazz, elementList);
      } else {
        classToVariableListMap.get(clazz).add(rootElement);
      }
    }
    return classToVariableListMap;
  }

  private boolean isParcelableArray(final Element field) {
    TypeMirror parcelable = processingEnv.getElementUtils().getTypeElement("android.os.Parcelable").asType();
    return processingEnv.getTypeUtils().isAssignable(((ArrayType) field.asType()).getComponentType(), parcelable);
  }

  private String getClassNameAsString(final Element field) {
    final String[] split = field.asType().toString().split("\\.");
    return split[split.length-1].replace("[]", "Array");
  }

  private boolean isArray(final Element element) {
    return element.asType().getKind() == TypeKind.ARRAY;
  }

  private boolean ofClass(Element element, Class clazz) {
    return clazz.getName().equals(element.asType().toString());
  }

  private String capitalizeFirstLetter(final String input) {
    return input.substring(0, 1).toUpperCase().concat(input.substring(1));
  }

  private String lowerCaseFirstLetter(final String input) {
    return input.substring(0, 1).toLowerCase().concat(input.substring(1));
  }

  private boolean isBundle(final Element field) {
    TypeMirror bundle = processingEnv.getElementUtils().getTypeElement("android.os.Bundle").asType();
    return processingEnv.getTypeUtils().isAssignable(field.asType(), bundle);
  }

  private boolean isParcelable(final Element field) {
    TypeMirror parcelable = processingEnv.getElementUtils().getTypeElement("android.os.Parcelable").asType();
    return processingEnv.getTypeUtils().isAssignable(field.asType(), parcelable);
  }

  private boolean isSerializable(final Element field) {
    TypeMirror serializable = processingEnv.getElementUtils().getTypeElement("java.io.Serializable").asType();
    return processingEnv.getTypeUtils().isAssignable(field.asType(), serializable);
  }
}
