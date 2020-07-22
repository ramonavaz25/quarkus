package io.quarkus.qrs.deployment.framework;

import static io.quarkus.qrs.deployment.framework.QrsDotNames.BLOCKING;
import static io.quarkus.qrs.deployment.framework.QrsDotNames.CONSUMES;
import static io.quarkus.qrs.deployment.framework.QrsDotNames.PATH;
import static io.quarkus.qrs.deployment.framework.QrsDotNames.PRODUCES;
import static io.quarkus.qrs.deployment.framework.QrsDotNames.QUERY_PARAM;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jboss.jandex.AnnotationInstance;
import org.jboss.jandex.AnnotationTarget;
import org.jboss.jandex.ClassInfo;
import org.jboss.jandex.DotName;
import org.jboss.jandex.IndexView;
import org.jboss.jandex.MethodInfo;

import io.quarkus.arc.processor.DotNames;
import io.quarkus.arc.runtime.BeanContainer;
import io.quarkus.deployment.GeneratedClassGizmoAdaptor;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.builditem.GeneratedClassBuildItem;
import io.quarkus.deployment.util.AsmUtil;
import io.quarkus.deployment.util.HashUtil;
import io.quarkus.gizmo.ClassCreator;
import io.quarkus.gizmo.MethodCreator;
import io.quarkus.gizmo.ResultHandle;
import io.quarkus.qrs.runtime.QrsRecorder;
import io.quarkus.qrs.runtime.model.MethodParameter;
import io.quarkus.qrs.runtime.model.ParameterType;
import io.quarkus.qrs.runtime.model.ResourceClass;
import io.quarkus.qrs.runtime.model.ResourceMethod;
import io.quarkus.qrs.runtime.spi.EndpointInvoker;

public class EndpointIndexer {

    public static ResourceClass createEndpoints(IndexView index, ClassInfo classInfo, BeanContainer beanContainer,
            BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer, QrsRecorder recorder) {
        List<ResourceMethod> methods = createEndpoints(index, classInfo, classInfo, new HashSet<>(),
                generatedClassBuildItemBuildProducer, recorder);
        ResourceClass clazz = new ResourceClass();
        clazz.getMethods().addAll(methods);
        clazz.setClassName(classInfo.name().toString());
        AnnotationInstance pathAnnotation = classInfo.classAnnotation(PATH);
        if (pathAnnotation != null) {
            String path = pathAnnotation.value().asString();
            if (!path.startsWith("/")) {
                path = "/" + path;
            }
            clazz.setPath(path);
        }
        clazz.setFactory(recorder.factory(clazz.getClassName(), beanContainer));
        return clazz;
    }

    private static List<ResourceMethod> createEndpoints(IndexView index, ClassInfo currentClassInfo,
            ClassInfo actualEndpointInfo, Set<String> seenMethods,
            BuildProducer<GeneratedClassBuildItem> generatedClassBuildItemBuildProducer, QrsRecorder recorder) {
        List<ResourceMethod> ret = new ArrayList<>();
        String[] classProduces = readStringArrayValue(currentClassInfo.classAnnotation(QrsDotNames.PRODUCES));
        String[] classConsumes = readStringArrayValue(currentClassInfo.classAnnotation(QrsDotNames.CONSUMES));
        AnnotationInstance pathAnnotation = actualEndpointInfo.classAnnotation(PATH);

        for (DotName httpMethod : QrsDotNames.JAXRS_METHOD_ANNOTATIONS) {
            List<AnnotationInstance> foundMethods = currentClassInfo.annotations().get(httpMethod);
            if (foundMethods != null) {
                for (AnnotationInstance annotation : foundMethods) {
                    MethodInfo info = annotation.target().asMethod();
                    String descriptor = info.name() + ":" + AsmUtil.getDescriptor(info, s -> null);
                    if (seenMethods.contains(descriptor)) {
                        continue;
                    }
                    seenMethods.add(descriptor);
                    String methodPath = readStringValue(info.annotation(PATH));
                    if (methodPath != null) {
                        if (!methodPath.startsWith("/")) {
                            methodPath = "/" + methodPath;
                        }
                    }
                    if (pathAnnotation != null) {
                        methodPath = appendPath(pathAnnotation.value().asString(), methodPath);
                    }
                    Map<DotName, AnnotationInstance>[] parameterAnnotations = new Map[info.parameters().size()];
                    MethodParameter[] methodParameters = new MethodParameter[info.parameters()
                            .size()];
                    for (int paramPos = 0; paramPos < info.parameters().size(); ++paramPos) {
                        parameterAnnotations[paramPos] = new HashMap<>();
                    }
                    for (AnnotationInstance i : info.annotations()) {
                        if (i.target().kind() == AnnotationTarget.Kind.METHOD_PARAMETER) {
                            parameterAnnotations[i.target().asMethodParameter().position()].put(i.name(), i);
                        }
                    }
                    for (int i = 0; i < methodParameters.length; ++i) {
                        Map<DotName, AnnotationInstance> anns = parameterAnnotations[i];

                        String name = null;
                        AnnotationInstance pathParam = anns.get(QrsDotNames.PATH_PARAM);
                        AnnotationInstance queryParam = anns.get(QUERY_PARAM);
                        AnnotationInstance headerParam = anns.get(QrsDotNames.HEADER_PARAM);
                        AnnotationInstance formParam = anns.get(QrsDotNames.FORM_PARAM);
                        ParameterType type;
                        if (moreThanOne(pathParam, queryParam, headerParam, formParam)) {
                            throw new RuntimeException(
                                    "Cannot have more than one of @PathParam, @QueryParam, @HeaderParam, @FormParam on "
                                            + info);
                        } else if (pathParam != null) {
                            name = pathParam.value().asString();
                            type = ParameterType.PATH;
                        } else if (queryParam != null) {
                            name = queryParam.value().asString();
                            type = ParameterType.QUERY;
                        } else if (headerParam != null) {
                            name = headerParam.value().asString();
                            type = ParameterType.HEADER;
                        } else if (formParam != null) {
                            name = formParam.value().asString();
                            type = ParameterType.FORM;
                        } else {
                            throw new RuntimeException("Not implemented yet " + info);
                        }
                        methodParameters[i] = new MethodParameter(name,
                                info.parameters().get(i).asClassType().name().toString(), type);
                    }

                    String[] produces = readStringArrayValue(info.annotation(PRODUCES), classProduces);
                    String[] consumes = readStringArrayValue(info.annotation(CONSUMES), classConsumes);
                    ResourceMethod method = new ResourceMethod()
                            .setMethod(annotationToMethod(httpMethod))
                            .setPath(methodPath)
                            .setConsumes(consumes)
                            .setName(info.name())
                            .setBlocking(info.annotation(BLOCKING) != null)
                            .setParameters(methodParameters)
                            .setReturnType(info.returnType().asClassType().toString())
                            .setProduces(produces);

                    StringBuilder sigBuilder = new StringBuilder();
                    sigBuilder.append(method.getName())
                            .append(method.getReturnType());
                    for (MethodParameter t : method.getParameters()) {
                        sigBuilder.append(t);
                    }
                    String baseName = currentClassInfo.name() + "$qrsinvoker$" + method.getName() + "_"
                            + HashUtil.sha1(sigBuilder.toString());
                    try (ClassCreator classCreator = new ClassCreator(
                            new GeneratedClassGizmoAdaptor(generatedClassBuildItemBuildProducer, true), baseName, null,
                            Object.class.getName(), EndpointInvoker.class.getName())) {
                        MethodCreator mc = classCreator.getMethodCreator("invoke", Object.class, Object.class, Object[].class);
                        ResultHandle[] args = new ResultHandle[method.getParameters().length];
                        ResultHandle array = mc.getMethodParam(1);
                        for (int i = 0; i < method.getParameters().length; ++i) {
                            args[i] = mc.readArrayValue(array, i);
                        }
                        ResultHandle res = mc.invokeVirtualMethod(info, mc.getMethodParam(0), args);
                        mc.returnValue(res);
                    }
                    method.setInvoker(recorder.invoker(baseName));

                    ret.add(method);
                }
            }
        }
        DotName superClassName = currentClassInfo.superName();
        if (superClassName != null && !superClassName.equals(DotNames.OBJECT)) {
            ClassInfo superClass = index.getClassByName(superClassName);
            ret.addAll(createEndpoints(index, superClass, actualEndpointInfo, seenMethods,
                    generatedClassBuildItemBuildProducer, recorder));
        }
        return ret;
    }

    private static boolean moreThanOne(AnnotationInstance... annotations) {
        boolean oneNonNull = false;
        for (AnnotationInstance annotation : annotations) {
            if (annotation != null) {
                if (oneNonNull)
                    return true;
                oneNonNull = true;
            }
        }
        return false;
    }

    private static String[] readStringArrayValue(AnnotationInstance annotation, String[] defaultValue) {
        String[] read = readStringArrayValue(annotation);
        if (read == null) {
            return defaultValue;
        }
        return read;
    }

    private static String[] readStringArrayValue(AnnotationInstance annotation) {
        if (annotation == null) {
            return null;
        }
        return annotation.value().asStringArray();
    }

    private static String annotationToMethod(DotName httpMethod) {
        if (httpMethod.equals(QrsDotNames.GET)) {
            return "GET";
        } else if (httpMethod.equals(QrsDotNames.POST)) {
            return "POST";
        } else if (httpMethod.equals(QrsDotNames.HEAD)) {
            return "HEAD";
        } else if (httpMethod.equals(QrsDotNames.PUT)) {
            return "PUT";
        } else if (httpMethod.equals(QrsDotNames.DELETE)) {
            return "DELETE";
        } else if (httpMethod.equals(QrsDotNames.PATCH)) {
            return "PATCH";
        } else if (httpMethod.equals(QrsDotNames.OPTIONS)) {
            return "OPTIONS";
        }
        throw new IllegalStateException("Unknown HTTP method annotation " + httpMethod);
    }

    public static String readStringValue(AnnotationInstance annotation, String defaultValue) {
        String val = readStringValue(annotation);
        return val == null ? defaultValue : val;
    }

    public static String readStringValue(AnnotationInstance annotationInstance) {
        String classProduces = null;
        if (annotationInstance != null) {
            classProduces = annotationInstance.value().asString();
        }
        return classProduces;
    }

    private static String appendPath(String prefix, String suffix) {
        if (prefix == null) {
            return suffix;
        } else if (suffix == null) {
            return prefix;
        }
        if ((prefix.endsWith("/") && !suffix.startsWith("/")) ||
                (!prefix.endsWith("/") && suffix.startsWith("/"))) {
            return prefix + suffix;
        } else if (prefix.endsWith("/")) {
            return prefix.substring(0, prefix.length() - 1) + suffix;
        } else {
            return prefix + "/" + suffix;
        }
    }

}