/*
 * Copyright 2017-2020 original authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.micronaut.inject.writer;

import io.micronaut.context.AbstractBeanDefinition;
import io.micronaut.context.AbstractConstructorInjectionPoint;
import io.micronaut.context.AbstractExecutableMethod;
import io.micronaut.context.AbstractParametrizedBeanDefinition;
import io.micronaut.context.BeanContext;
import io.micronaut.context.BeanRegistration;
import io.micronaut.context.BeanResolutionContext;
import io.micronaut.context.DefaultBeanContext;
import io.micronaut.context.annotation.Bean;
import io.micronaut.context.annotation.ConfigurationBuilder;
import io.micronaut.context.annotation.ConfigurationInject;
import io.micronaut.context.annotation.ConfigurationProperties;
import io.micronaut.context.annotation.ConfigurationReader;
import io.micronaut.context.annotation.DefaultScope;
import io.micronaut.context.annotation.EachBean;
import io.micronaut.context.annotation.EachProperty;
import io.micronaut.context.annotation.Parameter;
import io.micronaut.context.annotation.Primary;
import io.micronaut.context.annotation.Property;
import io.micronaut.context.annotation.Provided;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.AnnotationMetadata;
import io.micronaut.core.annotation.AnnotationMetadataProvider;
import io.micronaut.core.annotation.AnnotationUtil;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Internal;
import io.micronaut.core.annotation.NonNull;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.beans.BeanConstructor;
import io.micronaut.core.bind.annotation.Bindable;
import io.micronaut.core.naming.NameUtils;
import io.micronaut.core.reflect.ReflectionUtils;
import io.micronaut.core.type.Argument;
import io.micronaut.core.util.ArrayUtils;
import io.micronaut.core.util.CollectionUtils;
import io.micronaut.core.util.StringUtils;
import io.micronaut.inject.*;
import io.micronaut.inject.annotation.AnnotationMetadataHierarchy;
import io.micronaut.inject.annotation.AnnotationMetadataReference;
import io.micronaut.inject.annotation.AnnotationMetadataWriter;
import io.micronaut.inject.annotation.DefaultAnnotationMetadata;
import io.micronaut.inject.ast.*;
import io.micronaut.inject.configuration.ConfigurationMetadataBuilder;
import io.micronaut.inject.configuration.PropertyMetadata;
import io.micronaut.inject.processing.JavaModelUtils;
import io.micronaut.inject.visitor.VisitorContext;
import jakarta.inject.Singleton;
import org.jetbrains.annotations.NotNull;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.GeneratorAdapter;
import org.objectweb.asm.signature.SignatureVisitor;
import org.objectweb.asm.signature.SignatureWriter;

import java.io.IOException;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * <p>Responsible for building {@link BeanDefinition} instances at compile time. Uses ASM build the class definition.</p>
 * <p>
 * <p>Should be used from AST frameworks to build bean definitions from source code data.</p>
 * <p>
 * <p>For example:</p>
 *
 * <pre>
 *     {@code
 *
 *          BeanDefinitionWriter writer = new BeanDefinitionWriter("my.package", "MyClass", "javax.inject.Singleton", true)
 *          writer.visitBeanDefinitionConstructor()
 *          writer.visitFieldInjectionPoint("my.Qualifier", false, "my.package.MyDependency", "myfield" )
 *          writer.visitBeanDefinitionEnd()
 *          writer.writeTo(new File(..))
 *     }
 * </pre>
 *
 * @author Graeme Rocher
 * @see BeanDefinition
 * @since 1.0
 */
@Internal
public class BeanDefinitionWriter extends AbstractClassFileWriter implements BeanDefinitionVisitor {
    private static final String ANN_CONSTRAINT = "javax.validation.Constraint";
    private static final Constructor<AbstractBeanDefinition> CONSTRUCTOR_ABSTRACT_BEAN_DEFINITION = ReflectionUtils.findConstructor(
            AbstractBeanDefinition.class,
            Class.class,
            AnnotationMetadata.class,
            boolean.class,
            Argument[].class)
            .orElseThrow(() -> new ClassGenerationException("Invalid version of Micronaut present on the class path"));

    private static final Constructor<AbstractConstructorInjectionPoint> CONSTRUCTOR_ABSTRACT_CONSTRUCTOR_IP = ReflectionUtils.findConstructor(
            AbstractConstructorInjectionPoint.class,
            BeanDefinition.class)
            .orElseThrow(() -> new ClassGenerationException("Invalid version of Micronaut present on the class path"));

    private static final org.objectweb.asm.commons.Method METHOD_MAP_OF = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredInternalMethod(
                    CollectionUtils.class,
                    "mapOf",
                    Object[].class
            )
    );
    private static final Method POST_CONSTRUCT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "postConstruct", BeanResolutionContext.class, BeanContext.class, Object.class);

    private static final Method INJECT_BEAN_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "injectBean", BeanResolutionContext.class, BeanContext.class, Object.class);

    private static final Method PRE_DESTROY_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "preDestroy", BeanResolutionContext.class, BeanContext.class, Object.class);

    private static final Method ADD_FIELD_INJECTION_POINT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addInjectionPoint", Class.class, Class.class, String.class, AnnotationMetadata.class, Argument[].class, boolean.class);

    private static final Method ADD_METHOD_INJECTION_POINT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addInjectionPoint", Class.class, String.class, Argument[].class, AnnotationMetadata.class, boolean.class);

    private static final Method ADD_POST_CONSTRUCT_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addPostConstruct", Class.class, String.class, Argument[].class, AnnotationMetadata.class, boolean.class);

    private static final Method ADD_PRE_DESTROY_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addPreDestroy", Class.class, String.class, Argument[].class, AnnotationMetadata.class, boolean.class);

    private static final Method ADD_EXECUTABLE_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "addExecutableMethod", ExecutableMethod.class);

    private static final Method GET_BEAN_FOR_CONSTRUCTOR_ARGUMENT = getBeanLookupMethod("getBeanForConstructorArgument");

    private static final Method GET_BEAN_REGISTRATIONS_FOR_CONSTRUCTOR_ARGUMENT = getBeanLookupMethod("getBeanRegistrationsForConstructorArgument");

    private static final Method GET_BEAN_REGISTRATION_FOR_CONSTRUCTOR_ARGUMENT = getBeanLookupMethod("getBeanRegistrationForConstructorArgument");

    private static final Method GET_BEANS_OF_TYPE_FOR_CONSTRUCTOR_ARGUMENT = getBeanLookupMethod("getBeansOfTypeForConstructorArgument");

    private static final Method GET_VALUE_FOR_CONSTRUCTOR_ARGUMENT = getBeanLookupMethod("getValueForConstructorArgument");

    private static final Method GET_BEAN_FOR_FIELD = getBeanLookupMethod("getBeanForField");

    private static final Method GET_BEAN_REGISTRATIONS_FOR_FIELD = getBeanLookupMethod("getBeanRegistrationsForField");

    private static final Method GET_BEAN_REGISTRATION_FOR_FIELD = getBeanLookupMethod("getBeanRegistrationForField");

    private static final Method GET_BEANS_OF_TYPE_FOR_FIELD = getBeanLookupMethod("getBeansOfTypeForField");

    private static final Method GET_VALUE_FOR_FIELD = getBeanLookupMethod("getValueForField");

    private static final Method GET_VALUE_FOR_PATH = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "getValueForPath", BeanResolutionContext.class, BeanContext.class, Argument.class, String.class);

    private static final Method CONTAINS_VALUE_FOR_FIELD = getBeanLookupMethod("containsValueForField");

    private static final Method CONTAINS_PROPERTIES_METHOD = ReflectionUtils.getRequiredInternalMethod(AbstractBeanDefinition.class, "containsProperties", BeanResolutionContext.class, BeanContext.class);

    private static final Method GET_BEAN_FOR_METHOD_ARGUMENT = getBeanLookupMethodForArgument("getBeanForMethodArgument");

    private static final Method GET_BEAN_REGISTRATIONS_FOR_METHOD_ARGUMENT = getBeanLookupMethodForArgument("getBeanRegistrationsForMethodArgument");

    private static final Method GET_BEAN_REGISTRATION_FOR_METHOD_ARGUMENT = getBeanLookupMethodForArgument("getBeanRegistrationForMethodArgument");

    private static final Method GET_BEANS_OF_TYPE_FOR_METHOD_ARGUMENT = getBeanLookupMethodForArgument("getBeansOfTypeForMethodArgument");

    private static final Method GET_VALUE_FOR_METHOD_ARGUMENT = getBeanLookupMethodForArgument("getValueForMethodArgument");

    private static final Method CONTAINS_VALUE_FOR_METHOD_ARGUMENT = getBeanLookupMethodForArgument("containsValueForMethodArgument");

    private static final org.objectweb.asm.commons.Method BEAN_DEFINITION_CLASS_CONSTRUCTOR = new org.objectweb.asm.commons.Method(CONSTRUCTOR_NAME, getConstructorDescriptor(
            Class.class, AnnotationMetadata.class, boolean.class, Argument[].class
    ));

    private static final org.objectweb.asm.commons.Method BEAN_DEFINITION_METHOD_CONSTRUCTOR = new org.objectweb.asm.commons.Method(CONSTRUCTOR_NAME, getConstructorDescriptor(
            Class.class, Class.class, String.class, AnnotationMetadata.class, boolean.class, Argument[].class
    ));

    private static final org.objectweb.asm.commons.Method BEAN_DEFINITION_FIELD_CONSTRUCTOR = new org.objectweb.asm.commons.Method(CONSTRUCTOR_NAME, getConstructorDescriptor(
            Class.class, Class.class, String.class, AnnotationMetadata.class, boolean.class
    ));

    private static final Type TYPE_ABSTRACT_BEAN_DEFINITION = Type.getType(AbstractBeanDefinition.class);
    private static final Type TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION = Type.getType(AbstractParametrizedBeanDefinition.class);
    private static final org.objectweb.asm.commons.Method METHOD_OPTIONAL_EMPTY = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredMethod(Optional.class, "empty")
    );
    private static final Type TYPE_OPTIONAL = Type.getType(Optional.class);
    private static final org.objectweb.asm.commons.Method METHOD_OPTIONAL_OF = org.objectweb.asm.commons.Method.getMethod(
            ReflectionUtils.getRequiredMethod(Optional.class, "of", Object.class)
    );
    private static final org.objectweb.asm.commons.Method METHOD_INVOKE_CONSTRUCTOR = org.objectweb.asm.commons.Method.getMethod(ReflectionUtils.getRequiredMethod(
            ConstructorInjectionPoint.class,
            "invoke",
            Object[].class
    ));
    private static final String METHOD_DESCRIPTOR_CONSTRUCTOR_INSTANTIATE = getMethodDescriptor(Object.class, Arrays.asList(
            BeanResolutionContext.class,
            BeanContext.class,
            List.class,
            BeanDefinition.class,
            BeanConstructor.class,
            Object[].class
    ));
    private static final String METHOD_DESCRIPTOR_INTERCEPTED_LIFECYCLE = getMethodDescriptor(Object.class, Arrays.asList(
            BeanResolutionContext.class,
            BeanContext.class,
            BeanDefinition.class,
            ExecutableMethod.class,
            Object.class
    ));
    private static final Method METHOD_GET_BEAN = ReflectionUtils.getRequiredInternalMethod(DefaultBeanContext.class, "getBean", BeanResolutionContext.class, Class.class);
    private static final Type TYPE_RESOLUTION_CONTEXT = Type.getType(BeanResolutionContext.class);
    private static final Type TYPE_BEAN_CONTEXT = Type.getType(BeanContext.class);
    private static final Type TYPE_BEAN_DEFINITION = Type.getType(BeanDefinition.class);
    private static final String METHOD_DESCRIPTOR_INITIALIZE = Type.getMethodDescriptor(Type.getType(Object.class), Type.getType(BeanResolutionContext.class), Type.getType(BeanContext.class), Type.getType(Object.class));
    private final ClassWriter classWriter;
    private final String beanFullClassName;
    private final String beanDefinitionName;
    private final String beanDefinitionInternalName;
    private final Type beanType;
    private final Type providedType;
    private final Set<Class> interfaceTypes;
    private final Map<String, GeneratorAdapter> loadTypeMethods = new LinkedHashMap<>();
    private final Map<String, ExecutableMethodWriter> methodExecutors = new LinkedHashMap<>();
    private final Map<String, ClassWriter> innerClasses = new LinkedHashMap<>(2);
    private final String providedBeanClassName;
    private final String packageName;
    private final String beanSimpleClassName;
    private final Type beanDefinitionType;
    private final boolean isInterface;
    private final boolean isConfigurationProperties;
    private final ConfigurationMetadataBuilder<?> metadataBuilder;
    private final Element beanProducingElement;
    private final ClassElement beanTypeElement;
    private GeneratorAdapter constructorVisitor;
    private GeneratorAdapter buildMethodVisitor;
    private GeneratorAdapter injectMethodVisitor;
    private Label injectEnd = null;
    private GeneratorAdapter preDestroyMethodVisitor;
    private GeneratorAdapter postConstructMethodVisitor;
    private GeneratorAdapter interceptedDisposeMethod;
    private int methodExecutorIndex = 0;
    private int currentFieldIndex = 0;
    private int currentMethodIndex = 0;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "build" method signature. See BeanFactory
    private int buildMethodLocalCount = 4;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "injectBean" method signature. See AbstractBeanDefinition
    private int injectMethodLocalCount = 4;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "initialize" method signature. See InitializingBeanDefinition
    private int postConstructMethodLocalCount = 4;

    // 0 is this, while 1,2 and 3 are the first 3 parameters in the "dispose" method signature. See DisposableBeanDefinition
    private int preDestroyMethodLocalCount = 4;

    // the instance being built position in the index
    private int buildInstanceIndex;
    private int argsIndex = -1;
    private int injectInstanceIndex;
    private int postConstructInstanceIndex;
    private int preDestroyInstanceIndex;
    private boolean beanFinalized = false;
    private Type superType = TYPE_ABSTRACT_BEAN_DEFINITION;
    private boolean isSuperFactory = false;
    private final AnnotationMetadata annotationMetadata;
    private ConfigBuilderState currentConfigBuilderState;
    private int optionalInstanceIndex;
    private boolean preprocessMethods = false;
    private Map<String, Map<String, ClassElement>> typeArguments;
    private List<MethodVisitData> postConstructMethodVisits = new ArrayList<>(2);
    private List<MethodVisitData> preDestroyMethodVisits = new ArrayList<>(2);
    private String interceptedType;

    private List<Runnable> deferredInjectionPoints = new ArrayList<>();
    private int innerClassIndex;

    /**
     * Creates a bean definition writer.
     *
     * @param classElement    The class element
     * @param metadataBuilder The configuration metadata builder
     * @param visitorContext  The visitor context
     */
    public BeanDefinitionWriter(ClassElement classElement,
                                ConfigurationMetadataBuilder<?> metadataBuilder,
                                VisitorContext visitorContext) {
        this(classElement, OriginatingElements.of(classElement), metadataBuilder, visitorContext, null);
    }

    /**
     * Creates a bean definition writer.
     *
     * @param classElement        The class element
     * @param originatingElements The originating elements
     * @param metadataBuilder     The configuration metadata builder
     * @param visitorContext      The visitor context
     */
    public BeanDefinitionWriter(ClassElement classElement,
                                OriginatingElements originatingElements,
                                ConfigurationMetadataBuilder<?> metadataBuilder,
                                VisitorContext visitorContext) {
        this(classElement, originatingElements, metadataBuilder, visitorContext, null);
    }

    /**
     * Creates a bean definition writer.
     *
     * @param beanProducingElement The bean producing element
     * @param originatingElements  The originating elements
     * @param metadataBuilder      The configuration metadata builder
     * @param visitorContext       The visitor context
     * @param uniqueIdentifier     An optional unique identifier to include in the bean name
     */
    public BeanDefinitionWriter(Element beanProducingElement,
                                OriginatingElements originatingElements,
                                ConfigurationMetadataBuilder<?> metadataBuilder,
                                VisitorContext visitorContext,
                                @Nullable Integer uniqueIdentifier) {
        super(originatingElements);
        this.metadataBuilder = metadataBuilder;
        this.classWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        this.beanProducingElement = beanProducingElement;
        if (beanProducingElement instanceof ClassElement) {
            ClassElement classElement = (ClassElement) beanProducingElement;
            this.beanTypeElement = classElement;
            this.packageName = classElement.getPackageName();
            this.isInterface = classElement.isInterface();
            this.beanFullClassName = classElement.getName();
            this.beanSimpleClassName = classElement.getSimpleName();
            this.providedBeanClassName = beanFullClassName;
            this.beanDefinitionName = getBeanDefinitionName(packageName, beanSimpleClassName);
        } else if (beanProducingElement instanceof MethodElement) {
            MethodElement factoryMethodElement = (MethodElement) beanProducingElement;
            final ClassElement producedElement = factoryMethodElement.getGenericReturnType();
            this.beanTypeElement = producedElement;
            this.packageName = producedElement.getPackageName();
            this.isInterface = producedElement.isInterface();
            this.beanFullClassName = producedElement.getName();
            this.beanSimpleClassName = producedElement.getSimpleName();
            this.providedBeanClassName = producedElement.getName();
            String upperCaseMethodName = NameUtils.capitalize(factoryMethodElement.getName());
            if (uniqueIdentifier == null) {
                throw new IllegalArgumentException("Factory methods require passing a unique identifier");
            }
            final ClassElement declaringType = factoryMethodElement.getDeclaringType();
            this.beanDefinitionName = declaringType.getPackageName() + ".$" + declaringType.getSimpleName() + "$" + upperCaseMethodName + uniqueIdentifier + "Definition";
        } else if (beanProducingElement instanceof FieldElement) {
            FieldElement factoryMethodElement = (FieldElement) beanProducingElement;
            final ClassElement producedElement = factoryMethodElement.getGenericField();
            this.beanTypeElement = producedElement;
            this.packageName = producedElement.getPackageName();
            this.isInterface = producedElement.isInterface();
            this.beanFullClassName = producedElement.getName();
            this.beanSimpleClassName = producedElement.getSimpleName();
            this.providedBeanClassName = producedElement.getName();
            String fieldName = NameUtils.capitalize(factoryMethodElement.getName());
            if (uniqueIdentifier == null) {
                throw new IllegalArgumentException("Factory fields require passing a unique identifier");
            }
            final ClassElement declaringType = factoryMethodElement.getDeclaringType();
            this.beanDefinitionName = declaringType.getPackageName() + ".$" + declaringType.getSimpleName() + "$" + fieldName + uniqueIdentifier + "Definition";
        } else {
            throw new IllegalArgumentException("Unsupported element type: " + beanProducingElement.getClass().getName());
        }
        this.annotationMetadata = beanProducingElement.getAnnotationMetadata();
        this.beanDefinitionType = getTypeReferenceForName(this.beanDefinitionName);
        this.beanType = getTypeReferenceForName(beanFullClassName);
        this.providedType = getTypeReferenceForName(providedBeanClassName);
        this.beanDefinitionInternalName = getInternalName(this.beanDefinitionName);
        this.interfaceTypes = new TreeSet<>(Comparator.comparing(Class::getName));
        this.interfaceTypes.add(BeanFactory.class);
        this.isConfigurationProperties = annotationMetadata.hasDeclaredStereotype(ConfigurationProperties.class);
        validateExposedTypes(annotationMetadata, visitorContext);

    }

    private void validateExposedTypes(AnnotationMetadata annotationMetadata, VisitorContext visitorContext) {
        final String[] types = annotationMetadata.stringValues(Bean.class, "typed");
        if (ArrayUtils.isNotEmpty(types)) {
            for (String name : types) {
                final ClassElement exposedType = visitorContext.getClassElement(name).orElse(null);
                if (exposedType == null) {
                    visitorContext.fail("Bean defines an exposed type [" + name + "] that is not on the classpath", beanProducingElement);
                } else if (!beanTypeElement.isAssignable(exposedType)) {
                    visitorContext.fail("Bean defines an exposed type [" + name + "] that is not implemented by the bean type", beanProducingElement);
                }
            }
        }
    }

    @NonNull
    private static String getBeanDefinitionName(String packageName, String className) {
        return packageName + ".$" + className + "Definition";
    }

    /**
     * @return The name of the bean definition reference class.
     */
    @Override
    @NonNull
    public String getBeanDefinitionReferenceClassName() {
        return beanDefinitionName + BeanDefinitionReferenceWriter.REF_SUFFIX;
    }

    /**
     * @return The data for any post construct methods that were visited
     */
    public final List<MethodVisitData> getPostConstructMethodVisits() {
        return Collections.unmodifiableList(postConstructMethodVisits);
    }

    /**
     * @return The data for any pre destroy methods that were visited
     */
    public List<MethodVisitData> getPreDestroyMethodVisits() {
        return Collections.unmodifiableList(preDestroyMethodVisits);
    }

    /**
     * @return The underlying class writer
     */
    public ClassVisitor getClassWriter() {
        return classWriter;
    }

    @Override
    public boolean isInterface() {
        return isInterface;
    }

    @Override
    public boolean isSingleton() {
        return annotationMetadata.hasDeclaredStereotype(AnnotationUtil.SINGLETON);
    }

    @Override
    public void visitBeanDefinitionInterface(Class<? extends BeanDefinition> interfaceType) {
        this.interfaceTypes.add(interfaceType);
    }

    @Override
    public void visitSuperBeanDefinition(String name) {
        this.superType = getTypeReferenceForName(name);
    }

    @Override
    public void visitSuperBeanDefinitionFactory(String beanName) {
        visitSuperBeanDefinition(beanName);
        this.isSuperFactory = true;
    }

    @Override
    public String getBeanTypeName() {
        return beanFullClassName;
    }

    @Override
    public Type getProvidedType() {
        return providedType;
    }

    @Override
    public void setValidated(boolean validated) {
        if (validated) {
            this.interfaceTypes.add(ValidatedBeanDefinition.class);
        } else {
            this.interfaceTypes.remove(ValidatedBeanDefinition.class);
        }
    }

    @Override
    public void setInterceptedType(String typeName) {
        if (typeName != null) {
            this.interfaceTypes.add(AdvisedBeanType.class);
        }
        this.interceptedType = typeName;
    }

    @Override
    public Optional<Type> getInterceptedType() {
        return Optional.ofNullable(interceptedType)
                .map(BeanDefinitionWriter::getTypeReferenceForName);
    }

    @Override
    public boolean isValidated() {
        return this.interfaceTypes.contains(ValidatedBeanDefinition.class);
    }

    @Override
    public String getBeanDefinitionName() {
        return beanDefinitionName;
    }

    /**
     * @return The name of the bean definition class
     */
    public String getBeanDefinitionClassFile() {
        String className = getBeanDefinitionName();
        return getClassFileName(className);
    }

    /**
     * <p>In the case where the produced class is produced by a factory method annotated with
     * {@link io.micronaut.context.annotation.Bean} this method should be called.</p>
     *
     * @param factoryClass  The factory class
     * @param factoryMethod The factory method
     */
    public void visitBeanFactoryMethod(ClassElement factoryClass,
                                       MethodElement factoryMethod) {
        if (constructorVisitor != null) {
            throw new IllegalStateException("Only a single call to visitBeanFactoryMethod(..) is permitted");
        } else {
            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildFactoryMethodDefinition(factoryClass, factoryMethod);

            // now implement the constructor
            buildFactoryMethodClassConstructor(
                    factoryClass,
                    factoryMethod.getGenericReturnType(),
                    factoryMethod.getName(),
                    factoryMethod.getAnnotationMetadata(),
                    Arrays.asList(factoryMethod.getParameters())
            );

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }

    /**
     * <p>In the case where the produced class is produced by a factory field annotated with
     * {@link io.micronaut.context.annotation.Bean} this method should be called.</p>
     *
     * @param factoryClass  The factory class
     * @param factoryField The factory field
     */
    public void visitBeanFactoryField(ClassElement factoryClass, FieldElement factoryField) {
        if (constructorVisitor != null) {
            throw new IllegalStateException("Only a single call to visitBeanFactoryMethod(..) is permitted");
        } else {
            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildFactoryMethodDefinition(factoryClass, factoryField);

            // now implement the constructor
            buildFactoryFieldClassConstructor(
                    factoryClass,
                    factoryField.getGenericField(),
                    factoryField.getName(),
                    factoryField.getAnnotationMetadata(),
                    factoryField.isFinal()
            );

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }


    /**
     * Visits the constructor used to create the bean definition.
     *
     * @param constructor        The constructor
     * @param requiresReflection Whether invoking the constructor requires reflection
     * @param visitorContext     The visitor context
     */
    @Override
    public void visitBeanDefinitionConstructor(MethodElement constructor,
                                               boolean requiresReflection,
                                               VisitorContext visitorContext) {
        if (constructorVisitor == null) {
            applyConfigurationInjectionIfNecessary(constructor);
            // first build the constructor
            visitBeanDefinitionConstructorInternal(
                    constructor,
                    requiresReflection
            );

            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildMethodDefinition(constructor);

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }

    private void applyConfigurationInjectionIfNecessary(MethodElement constructor) {
        final boolean isRecordConfig = isRecordConfig(constructor);
        if (isRecordConfig || constructor.hasAnnotation(ConfigurationInject.class)) {
            final List<String> injectionTypes =
                    Arrays.asList(Property.class.getName(), Value.class.getName(), Parameter.class.getName(), AnnotationUtil.QUALIFIER, AnnotationUtil.INJECT);

            if (isRecordConfig) {
                final List<PropertyElement> beanProperties = constructor
                        .getDeclaringType()
                        .getBeanProperties();
                final ParameterElement[] parameters = constructor.getParameters();
                if (beanProperties.size() == parameters.length) {
                    for (int i = 0; i < parameters.length; i++) {
                        ParameterElement parameter = parameters[i];
                        final PropertyElement bp = beanProperties.get(i);
                        final AnnotationMetadata beanPropertyMetadata = bp.getAnnotationMetadata();
                        AnnotationMetadata annotationMetadata = parameter.getAnnotationMetadata();
                        if (injectionTypes.stream().noneMatch(beanPropertyMetadata::hasStereotype)) {
                            processConfigurationConstructorParameter(parameter, annotationMetadata);
                        }
                        if (annotationMetadata.hasStereotype(ANN_CONSTRAINT)) {
                            setValidated(true);
                        }
                    }
                } else {
                    processConfigurationInjectionConstructor(constructor, injectionTypes);
                }
            } else {
                processConfigurationInjectionConstructor(constructor, injectionTypes);
            }
        }

    }

    private void processConfigurationInjectionConstructor(MethodElement constructor, List<String> injectionTypes) {
        ParameterElement[] parameters = constructor.getParameters();
        for (ParameterElement parameter : parameters) {
            AnnotationMetadata annotationMetadata = parameter.getAnnotationMetadata();
            if (injectionTypes.stream().noneMatch(annotationMetadata::hasStereotype)) {
                processConfigurationConstructorParameter(parameter, annotationMetadata);
            }
            if (annotationMetadata.hasStereotype(ANN_CONSTRAINT)) {
                setValidated(true);
            }
        }
    }

    private void processConfigurationConstructorParameter(ParameterElement parameter, AnnotationMetadata annotationMetadata) {
        ClassElement parameterType = parameter.getGenericType();
        if (!parameterType.hasStereotype(AnnotationUtil.SCOPE)) {
            final PropertyMetadata pm = metadataBuilder.visitProperty(
                    parameterType.getName(),
                    parameter.getName(), parameter.getDocumentation().orElse(null),
                    annotationMetadata.stringValue(Bindable.class, "defaultValue").orElse(null)
            );
            parameter.annotate(Property.class, (builder) -> builder.member("name", pm.getPath()));
        }
    }

    private boolean isRecordConfig(MethodElement constructor) {
        ClassElement declaringType = constructor.getDeclaringType();
        return declaringType.isRecord() && declaringType.hasStereotype(ConfigurationReader.class);
    }

    @Override
    public void visitDefaultConstructor(AnnotationMetadata annotationMetadata, VisitorContext visitorContext) {
        if (constructorVisitor == null) {
            ClassElement bean = ClassElement.of(beanType.getClassName());
            MethodElement constructor = MethodElement.of(
                    bean,
                    annotationMetadata,
                    bean,
                    bean,
                    "<init>"
            );
            // first build the constructor
            visitBeanDefinitionConstructorInternal(
                    constructor,
                    false
            );

            // now prepare the implementation of the build method. See BeanFactory interface
            visitBuildMethodDefinition(constructor);

            // now override the injectBean method
            visitInjectMethodDefinition();
        }
    }

    /**
     * Finalize the bean definition to the given output stream.
     */
    @SuppressWarnings("Duplicates")
    @Override
    public void visitBeanDefinitionEnd() {
        if (classWriter == null) {
            throw new IllegalStateException("At least one called to visitBeanDefinitionConstructor(..) is required");
        }

        String[] interfaceInternalNames = new String[interfaceTypes.size()];
        Iterator<Class> j = interfaceTypes.iterator();
        for (int i = 0; i < interfaceInternalNames.length; i++) {
            interfaceInternalNames[i] = Type.getInternalName(j.next());
        }
        classWriter.visit(V1_8, ACC_SYNTHETIC,
                beanDefinitionInternalName,
                generateBeanDefSig(providedType.getInternalName()),
                isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION.getInternalName() : superType.getInternalName(),
                interfaceInternalNames);

        classWriter.visitAnnotation(TYPE_GENERATED.getDescriptor(), false);

        if (buildMethodVisitor == null) {
            throw new IllegalStateException("At least one call to visitBeanDefinitionConstructor() is required");
        }

        finalizeInjectMethod();
        finalizeBuildMethod();
        finalizeAnnotationMetadata();
        finalizeTypeArguments();

        if (preprocessMethods) {
            GeneratorAdapter requiresMethodProcessing = startPublicMethod(classWriter, "requiresMethodProcessing", boolean.class.getName());
            requiresMethodProcessing.push(true);
            requiresMethodProcessing.visitInsn(IRETURN);
            requiresMethodProcessing.visitMaxs(1, 1);
            requiresMethodProcessing.visitEnd();
        }

        for (Runnable fieldInjectionPoint : deferredInjectionPoints) {
            fieldInjectionPoint.run();
        }

        constructorVisitor.visitInsn(RETURN);
        constructorVisitor.visitMaxs(DEFAULT_MAX_STACK, 1);
        if (buildMethodVisitor != null) {
            buildMethodVisitor.visitInsn(ARETURN);
            buildMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, buildMethodLocalCount);
        }
        if (injectMethodVisitor != null) {
            injectMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, injectMethodLocalCount);
        }
        if (postConstructMethodVisitor != null) {
            postConstructMethodVisitor.visitVarInsn(ALOAD, postConstructInstanceIndex);
            postConstructMethodVisitor.visitInsn(ARETURN);
            postConstructMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, postConstructMethodLocalCount);
        }
        if (preDestroyMethodVisitor != null) {
            preDestroyMethodVisitor.visitVarInsn(ALOAD, preDestroyInstanceIndex);
            preDestroyMethodVisitor.visitInsn(ARETURN);
            preDestroyMethodVisitor.visitMaxs(DEFAULT_MAX_STACK, preDestroyMethodLocalCount);
        }
        if (interceptedDisposeMethod != null) {
            interceptedDisposeMethod.visitMaxs(1, 1);
            interceptedDisposeMethod.visitEnd();
        }

        getInterceptedType().ifPresent(t -> implementInterceptedTypeMethod(t, this.classWriter));

        for (GeneratorAdapter method : loadTypeMethods.values()) {
            method.visitMaxs(3, 1);
            method.visitEnd();
        }
        classWriter.visitEnd();
        this.beanFinalized = true;
    }

    private void finalizeTypeArguments() {
        if (CollectionUtils.isNotEmpty(typeArguments)) {
            GeneratorAdapter visitor = startPublicMethodZeroArgs(classWriter, Map.class, "getTypeArgumentsMap");
            int totalSize = typeArguments.size() * 2;
            // start a new array
            pushNewArray(visitor, Object.class, totalSize);
            int i = 0;
            for (Map.Entry<String, Map<String, ClassElement>> entry : typeArguments.entrySet()) {
                // use the property name as the key
                String typeName = entry.getKey();
                pushStoreStringInArray(visitor, i++, totalSize, typeName);
                // use the property type as the value
                pushStoreInArray(visitor, i++, totalSize, () ->
                        pushTypeArgumentElements(
                                beanDefinitionType,
                                classWriter,
                                visitor,
                                beanDefinitionName,
                                entry.getValue(),
                                loadTypeMethods
                        )
                );
            }
            // invoke the AbstractBeanDefinition.createMap method
            visitor.invokeStatic(Type.getType(CollectionUtils.class), METHOD_MAP_OF);
            visitor.returnValue();
            visitor.visitMaxs(1, 1);
            visitor.visitEnd();
        }
    }

    private void finalizeAnnotationMetadata() {
        if (annotationMetadata != null) {
            GeneratorAdapter annotationMetadataMethod = startProtectedMethod(
                    classWriter,
                    "resolveAnnotationMetadata",
                    AnnotationMetadata.class.getName()
            );
            lookupReferenceAnnotationMetadata(annotationMetadataMethod);
        }

        // method: boolean isSingleton()

        AnnotationMetadata annotationMetadata = this.annotationMetadata != null ? this.annotationMetadata : AnnotationMetadata.EMPTY_METADATA;
        writeBooleanMethod(classWriter, "isSingleton", () ->
                annotationMetadata.hasDeclaredStereotype(AnnotationUtil.SINGLETON) ||
                        annotationMetadata.stringValue(DefaultScope.class)
                                .map(t -> t.equals(Singleton.class.getName()) || t.equals(AnnotationUtil.SINGLETON))
                                .orElse(false));

        // method: boolean isIterable()
        writeBooleanMethod(classWriter, "isIterable", () ->
                annotationMetadata.hasDeclaredStereotype(EachProperty.class) ||
                        annotationMetadata.hasDeclaredStereotype(EachBean.class));

        // method: boolean isPrimary()
        writeBooleanMethod(classWriter, "isPrimary", () ->
                annotationMetadata.hasDeclaredStereotype(Primary.class));

        // method: boolean isProvided()
        writeBooleanMethod(classWriter, "isProvided", () ->
                annotationMetadata.hasDeclaredStereotype(Provided.class));

        // method: Optional<Class> getScope()
        GeneratorAdapter getScopeMethod = startPublicMethodZeroArgs(
                classWriter,
                Optional.class,
                "getScope"
        );
        getScopeMethod.loadThis();
        Optional<String> scope = annotationMetadata.getDeclaredAnnotationNameByStereotype(AnnotationUtil.SCOPE);
        if (scope.isPresent()) {
            getScopeMethod.push(getTypeReferenceForName(scope.get()));
            getScopeMethod.invokeStatic(
                    TYPE_OPTIONAL,
                    METHOD_OPTIONAL_OF
            );
        } else {
            getScopeMethod.invokeStatic(TYPE_OPTIONAL, METHOD_OPTIONAL_EMPTY);
        }

        getScopeMethod.returnValue();
        getScopeMethod.visitMaxs(1, 1);
        getScopeMethod.visitEnd();

    }

    private void lookupReferenceAnnotationMetadata(GeneratorAdapter annotationMetadataMethod) {
        annotationMetadataMethod.loadThis();
        annotationMetadataMethod.getStatic(getTypeReferenceForName(getBeanDefinitionReferenceClassName()), AbstractAnnotationMetadataWriter.FIELD_ANNOTATION_METADATA, Type.getType(AnnotationMetadata.class));
        annotationMetadataMethod.returnValue();
        annotationMetadataMethod.visitMaxs(1, 1);
        annotationMetadataMethod.visitEnd();
    }

    /**
     * @return The bytes of the class
     */
    public byte[] toByteArray() {
        if (!beanFinalized) {
            throw new IllegalStateException("Bean definition not finalized. Call visitBeanDefinitionEnd() first.");
        }
        return classWriter.toByteArray();
    }

    @Override
    public void accept(ClassWriterOutputVisitor visitor) throws IOException {
        try (OutputStream out = visitor.visitClass(getBeanDefinitionName(), getOriginatingElements())) {
            if (!innerClasses.isEmpty()) {
                for (Map.Entry<String, ClassWriter> entry : innerClasses.entrySet()) {
                    try (OutputStream constructorOut = visitor.visitClass(entry.getKey(), getOriginatingElements())) {
                        constructorOut.write(entry.getValue().toByteArray());
                    }
                }
            }
            try {
                for (ExecutableMethodWriter methodWriter : methodExecutors.values()) {
                    methodWriter.accept(visitor);
                }
            } catch (RuntimeException e) {
                Throwable cause = e.getCause();
                if (cause instanceof IOException) {
                    throw (IOException) cause;
                } else {
                    throw e;
                }
            }
            out.write(toByteArray());
        }
    }

    @Override
    public void visitSetterValue(
            TypedElement declaringType,
            MethodElement methodElement,
            boolean requiresReflection,
            boolean isOptional) {

        ParameterElement[] parameters = methodElement.getParameters();
        if (parameters.length != 1) {
            throw new IllegalArgumentException("Method must have exactly 1 argument");
        }
        Type declaringTypeRef = JavaModelUtils.getTypeReference(declaringType);

        // load 'this'
        constructorVisitor.visitVarInsn(ALOAD, 0);

        // 1st argument: The declaring type
        constructorVisitor.push(declaringTypeRef);

        // 2nd argument: The method name
        constructorVisitor.push(methodElement.getName());

        // 3rd argument: the argument types
        pushBuildArgumentsForMethod(
                declaringTypeRef.getClassName(),
                beanDefinitionType,
                classWriter,
                constructorVisitor,
                Arrays.asList(parameters),
                loadTypeMethods
        );

        // 4th argument: The annotation metadata
        AnnotationMetadata annotationMetadata = methodElement.getAnnotationMetadata();
        if (annotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
            constructorVisitor.visitInsn(ACONST_NULL);
        } else {
            AnnotationMetadataWriter.instantiateNewMetadata(
                    beanDefinitionType,
                    classWriter,
                    constructorVisitor,
                    (DefaultAnnotationMetadata) annotationMetadata,
                    loadTypeMethods
            );
        }

        // 5th  argument to addInjectionPoint: do we need reflection?
        constructorVisitor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

        // invoke add injection point method
        pushInvokeMethodOnSuperClass(constructorVisitor, ADD_METHOD_INJECTION_POINT_METHOD);

        if (!requiresReflection) {
            resolveBeanOrValueForSetter(
                    declaringTypeRef,
                    methodElement.getReturnType(), methodElement.getName(), parameters[0].getType(), GET_VALUE_FOR_METHOD_ARGUMENT, isOptional);
        }
        currentMethodIndex++;

    }

    @Override
    public void visitPostConstructMethod(TypedElement declaringType,
                                         MethodElement methodElement,
                                         boolean requiresReflection, VisitorContext visitorContext) {

        visitPostConstructMethodDefinition(false);

        final MethodVisitData methodVisitData = new MethodVisitData(
                declaringType,
                methodElement,
                requiresReflection
        );
        postConstructMethodVisits.add(methodVisitData);
        visitMethodInjectionPointInternal(methodVisitData,
                constructorVisitor,
                postConstructMethodVisitor,
                postConstructInstanceIndex,
                ADD_POST_CONSTRUCT_METHOD);
    }

    @Override
    public void visitPreDestroyMethod(TypedElement declaringType,
                                      MethodElement methodElement,
                                      boolean requiresReflection,
                                      VisitorContext visitorContext) {

        visitPreDestroyMethodDefinition(false);
        final MethodVisitData methodVisitData = new MethodVisitData(declaringType,
                methodElement,
                requiresReflection
        );
        preDestroyMethodVisits.add(methodVisitData);
        visitMethodInjectionPointInternal(
                methodVisitData,
                constructorVisitor,
                preDestroyMethodVisitor,
                preDestroyInstanceIndex,
                ADD_PRE_DESTROY_METHOD);
    }

    @Override
    public void visitMethodInjectionPoint(TypedElement declaringType,
                                          MethodElement methodElement,
                                          boolean requiresReflection, VisitorContext visitorContext) {
        applyConfigurationInjectionIfNecessary(methodElement);
        GeneratorAdapter constructorVisitor = this.constructorVisitor;
        GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;
        int injectInstanceIndex = this.injectInstanceIndex;

        visitMethodInjectionPointInternal(
                new MethodVisitData(
                        declaringType,
                        methodElement,
                        requiresReflection),
                constructorVisitor,
                injectMethodVisitor,
                injectInstanceIndex,
                ADD_METHOD_INJECTION_POINT_METHOD);
    }

    @Override
    public ExecutableMethodWriter visitExecutableMethod(TypedElement declaringBean,
                                                        MethodElement methodElement, VisitorContext visitorContext) {

        return visitExecutableMethod(
                declaringBean,
                methodElement,
                null,
                null
        );
    }

    /**
     * Visit a method that is to be made executable allow invocation of said method without reflection.
     *
     * @param declaringType                    The declaring type of the method. Either a Class or a string representing the
     *                                         name of the type
     * @param methodElement                    The method element
     * @param interceptedProxyClassName        The intercepted proxy class name
     * @param interceptedProxyBridgeMethodName The intercepted proxy bridge method name
     * @return The {@link ExecutableMethodWriter}.
     */
    public ExecutableMethodWriter visitExecutableMethod(TypedElement declaringType,
                                                        MethodElement methodElement,
                                                        String interceptedProxyClassName,
                                                        String interceptedProxyBridgeMethodName) {

        AnnotationMetadata annotationMetadata = methodElement.getAnnotationMetadata();
        String methodName = methodElement.getName();
        List<ParameterElement> argumentTypes = Arrays.asList(methodElement.getSuspendParameters());
        String methodKey = methodName +
                "(" +
                argumentTypes.stream()
                        .map(p -> p.getType().getName())
                        .collect(Collectors.joining(",")) +
                ")";

        if (methodExecutors.containsKey(methodKey)) {
            return methodExecutors.get(methodKey);
        }

        DefaultAnnotationMetadata.contributeDefaults(
                this.annotationMetadata,
                annotationMetadata
        );
        for (ParameterElement parameterElement : argumentTypes) {
            DefaultAnnotationMetadata.contributeDefaults(
                    this.annotationMetadata,
                    parameterElement.getAnnotationMetadata()
            );
        }
        String methodProxyShortName = "$exec" + ++methodExecutorIndex;
        String methodExecutorClassName = beanDefinitionName + "$" + methodProxyShortName;
        ParameterElement last = CollectionUtils.last(argumentTypes);
        boolean isSuspend = last != null && "kotlin.coroutines.Continuation".equals(last.getType().getName());

        if (annotationMetadata instanceof AnnotationMetadataHierarchy) {
            annotationMetadata = new AnnotationMetadataHierarchy(
                    new AnnotationMetadataReference(getBeanDefinitionReferenceClassName(), this.annotationMetadata),
                    ((AnnotationMetadataHierarchy) annotationMetadata).getDeclaredMetadata()
            );
        }

        boolean isInterface = declaringType.getType().isInterface();
        ExecutableMethodWriter executableMethodWriter = new ExecutableMethodWriter(
                methodExecutorClassName,
                this.isInterface || isInterface,
                (isInterface && !methodElement.isDefault()) || methodElement.isAbstract(),
                methodElement.isDefault(),
                isSuspend,
                this,
                annotationMetadata,
                interceptedProxyClassName,
                interceptedProxyBridgeMethodName
        );

        executableMethodWriter.visitMethod(
                declaringType,
                methodElement
        );

        methodExecutors.put(methodKey, executableMethodWriter);

        deferredInjectionPoints.add(() -> {

            if (constructorVisitor == null) {
                throw new IllegalStateException("Method visitBeanDefinitionConstructor(..) should be called first!");
            }

            constructorVisitor.visitVarInsn(ALOAD, 0);
            String methodExecutorInternalName = executableMethodWriter.getInternalName();
            constructorVisitor.visitTypeInsn(NEW, methodExecutorInternalName);
            constructorVisitor.visitInsn(DUP);
            constructorVisitor.visitMethodInsn(INVOKESPECIAL,
                    methodExecutorInternalName,
                    CONSTRUCTOR_NAME,
                    DESCRIPTOR_DEFAULT_CONSTRUCTOR,
                    false);

            pushInvokeMethodOnSuperClass(constructorVisitor, ADD_EXECUTABLE_METHOD);

        });

        return executableMethodWriter;
    }

    @Override
    public String toString() {
        return "BeanDefinitionWriter{" +
                "beanFullClassName='" + beanFullClassName + '\'' +
                '}';
    }

    @Override
    public String getPackageName() {
        return packageName;
    }

    @Override
    public String getBeanSimpleName() {
        return beanSimpleClassName;
    }

    @Override
    public AnnotationMetadata getAnnotationMetadata() {
        return this.annotationMetadata;
    }

    @Override
    public void visitConfigBuilderField(
            ClassElement type,
            String field,
            AnnotationMetadata annotationMetadata,
            ConfigurationMetadataBuilder metadataBuilder,
            boolean isInterface) {
        String factoryMethod = annotationMetadata
                .getValue(
                        ConfigurationBuilder.class,
                        "factoryMethod",
                        String.class)
                .orElse(null);

        if (StringUtils.isNotEmpty(factoryMethod)) {
            Type builderType = JavaModelUtils.getTypeReference(type);

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
            injectMethodVisitor.invokeStatic(
                    builderType,
                    org.objectweb.asm.commons.Method.getMethod(
                            builderType.getClassName() + " " + factoryMethod + "()"
                    )
            );

            injectMethodVisitor.putField(beanType, field, builderType);
        }

        this.currentConfigBuilderState = new ConfigBuilderState(
                type,
                field,
                false,
                annotationMetadata,
                metadataBuilder,
                isInterface);
    }

    @Override
    public void visitConfigBuilderMethod(
            ClassElement type,
            String methodName,
            AnnotationMetadata annotationMetadata,
            ConfigurationMetadataBuilder metadataBuilder,
            boolean isInterface) {

        String factoryMethod = annotationMetadata
                .getValue(
                        ConfigurationBuilder.class,
                        "factoryMethod",
                        String.class)
                .orElse(null);

        if (StringUtils.isNotEmpty(factoryMethod)) {
            Type builderType = JavaModelUtils.getTypeReference(type);

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
            injectMethodVisitor.invokeStatic(
                    builderType,
                    org.objectweb.asm.commons.Method.getMethod(
                            builderType.getClassName() + " " + factoryMethod + "()"
                    )
            );

            String propertyName = NameUtils.getPropertyNameForGetter(methodName);
            String setterName = NameUtils.setterNameFor(propertyName);

            injectMethodVisitor.invokeVirtual(beanType, org.objectweb.asm.commons.Method.getMethod(
                    "void " + setterName + "(" + builderType.getClassName() + ")"
            ));
        }

        this.currentConfigBuilderState = new ConfigBuilderState(type, methodName, true, annotationMetadata, metadataBuilder, isInterface);
    }

    @Override
    public void visitConfigBuilderDurationMethod(
            String prefix,
            ClassElement returnType,
            String methodName,
            String path) {
        visitConfigBuilderMethodInternal(
                prefix,
                returnType,
                methodName,
                ClassElement.of(Duration.class),
                Collections.emptyMap(),
                true,
                path
        );
    }

    @Override
    public void visitConfigBuilderMethod(
            String prefix,
            ClassElement returnType,
            String methodName,
            ClassElement paramType,
            Map<String, ClassElement> generics,
            String path) {

        visitConfigBuilderMethodInternal(
                prefix,
                returnType,
                methodName,
                paramType,
                generics,
                false,
                path
        );
    }

    @Override
    public void visitConfigBuilderEnd() {
        currentConfigBuilderState = null;
    }

    @Override
    public void setRequiresMethodProcessing(boolean shouldPreProcess) {
        this.preprocessMethods = shouldPreProcess;
    }

    @Override
    public void visitTypeArguments(Map<String, Map<String, ClassElement>> typeArguments) {
        this.typeArguments = typeArguments;
    }

    @Override
    public boolean requiresMethodProcessing() {
        return this.preprocessMethods;
    }

    @Override
    public void visitFieldInjectionPoint(
            TypedElement declaringType,
            FieldElement fieldElement,
            boolean requiresReflection) {
        // Implementation notes.
        // This method modifies the constructor adding addInjectPoint calls for each field that is annotated with @Inject
        // The constructor is a zero args constructor therefore there are no other local variables and "this" is stored in the 0 index.
        // The "currentFieldIndex" variable is used as a reference point for both the position of the local variable and also
        // for later on within the "build" method to in order to call "getBeanForField" with the appropriate index
        Method methodToInvoke;

        final ClassElement genericType = fieldElement.getGenericType();
        if (genericType.isAssignable(Collection.class) || genericType.isArray()) {
            ClassElement typeArgument = genericType.isArray() ? genericType.fromArray() : genericType.getFirstTypeArgument().orElse(null);
            if (typeArgument != null) {
                if (typeArgument.isAssignable(BeanRegistration.class)) {
                    methodToInvoke = GET_BEAN_REGISTRATIONS_FOR_FIELD;
                } else {
                    methodToInvoke = GET_BEANS_OF_TYPE_FOR_FIELD;
                }
            } else {
                methodToInvoke = GET_BEAN_FOR_FIELD;
            }
        } else {
            if (genericType.isAssignable(BeanRegistration.class)) {
                methodToInvoke = GET_BEAN_REGISTRATION_FOR_FIELD;
            } else {
                methodToInvoke = GET_BEAN_FOR_FIELD;
            }
        }
        visitFieldInjectionPointInternal(
                declaringType,
                fieldElement,
                requiresReflection,
                methodToInvoke,
                false
        );
    }

    @Override
    public void visitFieldValue(
            TypedElement declaringType,
            FieldElement fieldElement,
            boolean requiresReflection,
            boolean isOptional) {
        // Implementation notes.
        // This method modifies the constructor adding addInjectPoint calls for each field that is annotated with @Inject
        // The constructor is a zero args constructor therefore there are no other local variables and "this" is stored in the 0 index.
        // The "currentFieldIndex" variable is used as a reference point for both the position of the local variable and also
        // for later on within the "build" method to in order to call "getBeanForField" with the appropriate index
        visitFieldInjectionPointInternal(
                declaringType,
                fieldElement,
                requiresReflection,
                GET_VALUE_FOR_FIELD,
                isOptional);
    }

    private void visitConfigBuilderMethodInternal(
            String prefix,
            ClassElement returnType,
            String methodName,
            ClassElement paramType,
            Map<String, ClassElement> generics,
            boolean isDurationWithTimeUnit,
            String propertyPath) {

        if (currentConfigBuilderState != null) {
            Type builderType = currentConfigBuilderState.getType();
            String builderName = currentConfigBuilderState.getName();
            boolean isResolveBuilderViaMethodCall = currentConfigBuilderState.isMethod();
            GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;

            String propertyName = NameUtils.hyphenate(NameUtils.decapitalize(methodName.substring(prefix.length())), true);

            boolean zeroArgs = paramType == null;

            // Optional optional = AbstractBeanDefinition.getValueForPath(...)
            pushGetValueForPathCall(injectMethodVisitor, paramType, propertyName, propertyPath, zeroArgs, generics);

            Label ifEnd = new Label();
            // if(optional.isPresent())
            injectMethodVisitor.invokeVirtual(Type.getType(Optional.class), org.objectweb.asm.commons.Method.getMethod(
                    ReflectionUtils.getRequiredMethod(Optional.class, "isPresent")
            ));
            injectMethodVisitor.push(false);
            injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifEnd);
            if (zeroArgs) {
                pushOptionalGet(injectMethodVisitor);
                pushCastToType(injectMethodVisitor, boolean.class);
                injectMethodVisitor.push(false);
                injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, ifEnd);
            }

            injectMethodVisitor.visitLabel(new Label());

            String methodDescriptor;
            if (zeroArgs) {
                methodDescriptor = getMethodDescriptor(returnType, Collections.emptyList());
            } else if (isDurationWithTimeUnit) {
                methodDescriptor = getMethodDescriptor(returnType, Arrays.asList(ClassElement.of(long.class), ClassElement.of(TimeUnit.class)));
            } else {
                methodDescriptor = getMethodDescriptor(returnType, Collections.singleton(paramType));
            }

            Label tryStart = new Label();
            Label tryEnd = new Label();
            Label exceptionHandler = new Label();
            injectMethodVisitor.visitTryCatchBlock(tryStart, tryEnd, exceptionHandler, Type.getInternalName(NoSuchMethodError.class));

            injectMethodVisitor.visitLabel(tryStart);

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
            if (isResolveBuilderViaMethodCall) {
                String desc = builderType.getClassName() + " " + builderName + "()";
                injectMethodVisitor.invokeVirtual(beanType, org.objectweb.asm.commons.Method.getMethod(desc));
            } else {
                injectMethodVisitor.getField(beanType, builderName, builderType);
            }

            if (!zeroArgs) {
                pushOptionalGet(injectMethodVisitor);
                pushCastToType(injectMethodVisitor, paramType);
            }

            boolean isInterface = currentConfigBuilderState.isInterface();

            if (isDurationWithTimeUnit) {
                injectMethodVisitor.invokeVirtual(Type.getType(Duration.class), org.objectweb.asm.commons.Method.getMethod(
                        ReflectionUtils.getRequiredMethod(Duration.class, "toMillis")
                ));
                Type tu = Type.getType(TimeUnit.class);
                injectMethodVisitor.getStatic(tu, "MILLISECONDS", tu);
            }

            if (isInterface) {
                injectMethodVisitor.invokeInterface(builderType,
                        new org.objectweb.asm.commons.Method(methodName, methodDescriptor));
            } else {
                injectMethodVisitor.invokeVirtual(builderType,
                        new org.objectweb.asm.commons.Method(methodName, methodDescriptor));
            }

            if (returnType != PrimitiveElement.VOID) {
                injectMethodVisitor.pop();
            }
            injectMethodVisitor.visitJumpInsn(GOTO, tryEnd);
            injectMethodVisitor.visitLabel(exceptionHandler);
            injectMethodVisitor.pop();
            injectMethodVisitor.visitLabel(tryEnd);
            injectMethodVisitor.visitLabel(ifEnd);
        }
    }

    private void pushOptionalGet(GeneratorAdapter injectMethodVisitor) {
        injectMethodVisitor.visitVarInsn(ALOAD, optionalInstanceIndex);
        // get the value: optional.get()
        injectMethodVisitor.invokeVirtual(Type.getType(Optional.class), org.objectweb.asm.commons.Method.getMethod(
                ReflectionUtils.getRequiredMethod(Optional.class, "get")
        ));
    }

    private void pushGetValueForPathCall(GeneratorAdapter injectMethodVisitor, ClassElement propertyType, String propertyName, String propertyPath, boolean zeroArgs, Map<String, ClassElement> generics) {
        injectMethodVisitor.loadThis();
        injectMethodVisitor.loadArg(0); // the resolution context
        injectMethodVisitor.loadArg(1); // the bean context
        if (zeroArgs) {
            // if the parameter type is null this is a zero args method that expects a boolean flag
            buildArgument(
                    injectMethodVisitor,
                    propertyName,
                    Type.getType(Boolean.class)
            );
        } else {
            buildArgumentWithGenerics(
                    beanDefinitionType,
                    classWriter,
                    injectMethodVisitor,
                    propertyName,
                    JavaModelUtils.getTypeReference(propertyType),
                    propertyType,
                    generics,
                    new HashSet<>(),
                    loadTypeMethods
            );
        }

        injectMethodVisitor.push(propertyPath);
        // Optional optional = AbstractBeanDefinition.getValueForPath(...)
        injectMethodVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(GET_VALUE_FOR_PATH));
        injectMethodVisitor.visitVarInsn(ASTORE, optionalInstanceIndex);
        injectMethodVisitor.visitVarInsn(ALOAD, optionalInstanceIndex);
    }

    private void buildFactoryFieldClassConstructor(
            ClassElement factoryClass,
            ClassElement producedType,
            String fieldName,
            AnnotationMetadata fieldAnnotationMetadata,
            boolean isFinal) {
        Type factoryTypeRef = JavaModelUtils.getTypeReference(factoryClass);
        Type producedTypeRef = JavaModelUtils.getTypeReference(producedType);
        this.constructorVisitor = buildProtectedConstructor(BEAN_DEFINITION_FIELD_CONSTRUCTOR);

        GeneratorAdapter defaultConstructor = new GeneratorAdapter(
                startConstructor(classWriter),
                ACC_PUBLIC,
                CONSTRUCTOR_NAME,
                DESCRIPTOR_DEFAULT_CONSTRUCTOR
        );

        // ALOAD 0
        defaultConstructor.loadThis();

        // 1st argument: The factory type
        defaultConstructor.push(producedTypeRef);

        // 2nd argument: the produced type
        defaultConstructor.push(factoryTypeRef);

        // 3rd argument: The field name
        defaultConstructor.push(fieldName);

        // 4th argument: The annotation metadata
        pushAnnotationMetadata(fieldAnnotationMetadata, defaultConstructor);

        // 5th argument: is final
        defaultConstructor.push(isFinal);

        defaultConstructor.invokeConstructor(
                beanDefinitionType,
                BEAN_DEFINITION_FIELD_CONSTRUCTOR
        );

        defaultConstructor.visitInsn(RETURN);
        defaultConstructor.visitMaxs(DEFAULT_MAX_STACK, 1);
        defaultConstructor.visitEnd();
    }

    private void buildFactoryMethodClassConstructor(
            ClassElement factoryClass,
            ClassElement producedType,
            String methodName,
            AnnotationMetadata methodAnnotationMetadata,
            List<ParameterElement> argumentTypes) {
        Type factoryTypeRef = JavaModelUtils.getTypeReference(factoryClass);
        Type producedTypeRef = JavaModelUtils.getTypeReference(producedType);
        this.constructorVisitor = buildProtectedConstructor(BEAN_DEFINITION_METHOD_CONSTRUCTOR);

        GeneratorAdapter defaultConstructor = new GeneratorAdapter(
                startConstructor(classWriter),
                ACC_PUBLIC,
                CONSTRUCTOR_NAME,
                DESCRIPTOR_DEFAULT_CONSTRUCTOR
        );

        // ALOAD 0
        defaultConstructor.loadThis();

        // 1st argument: The factory type
        defaultConstructor.push(producedTypeRef);

        // 2nd argument: the produced type
        defaultConstructor.push(factoryTypeRef);

        // 3rd argument: The method name
        defaultConstructor.push(methodName);

        // 4th argument: The annotation metadata
        pushAnnotationMetadata(methodAnnotationMetadata, defaultConstructor);

        // 5th argument: Does the method require reflection
        defaultConstructor.push(false);

        // 6th argument: The arguments
        if (CollectionUtils.isNotEmpty(argumentTypes)) {
            pushBuildArgumentsForMethod(
                    beanDefinitionType.getClassName(),
                    beanDefinitionType,
                    classWriter,
                    defaultConstructor,
                    argumentTypes,
                    loadTypeMethods
            );
        } else {
            defaultConstructor.visitInsn(ACONST_NULL);
        }

        defaultConstructor.invokeConstructor(
                beanDefinitionType,
                BEAN_DEFINITION_METHOD_CONSTRUCTOR
        );

        defaultConstructor.visitInsn(RETURN);
        defaultConstructor.visitMaxs(DEFAULT_MAX_STACK, 1);
        defaultConstructor.visitEnd();
    }

    private void visitFieldInjectionPointInternal(
            TypedElement declaringType,
            FieldElement fieldElement,
            boolean requiresReflection,
            Method methodToInvoke,
            boolean isValueOptional) {
        AnnotationMetadata annotationMetadata = fieldElement.getAnnotationMetadata();
        DefaultAnnotationMetadata.contributeDefaults(this.annotationMetadata, annotationMetadata);
        // ready this
        GeneratorAdapter constructorVisitor = this.constructorVisitor;

        constructorVisitor.loadThis();

        // 1st argument: The declaring type
        Type declaringTypeRef = JavaModelUtils.getTypeReference(declaringType);
        constructorVisitor.push(declaringTypeRef);

        // 2nd argument: The field type
        constructorVisitor.push(JavaModelUtils.getTypeReference(fieldElement.getType()));

        // 3rd argument: The field name
        constructorVisitor.push(fieldElement.getName());

        // 4th argument: The annotation metadata
        pushAnnotationMetadata(annotationMetadata, constructorVisitor);

        // 5th argument: The type arguments
        Map<String, ClassElement> typeArguments = fieldElement.getGenericType().getTypeArguments();
        if (CollectionUtils.isNotEmpty(typeArguments)) {
            pushTypeArgumentElements(
                    beanDefinitionType,
                    classWriter,
                    constructorVisitor,
                    declaringType.getName(),
                    typeArguments,
                    loadTypeMethods
            );
        } else {
            constructorVisitor.visitInsn(ACONST_NULL);
        }

        // 6th argument: is reflection required?
        constructorVisitor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

        // invoke addInjectionPoint method
        pushInvokeMethodOnSuperClass(constructorVisitor, ADD_FIELD_INJECTION_POINT_METHOD);

        GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;

        Label falseCondition = null;
        if (isValueOptional) {
            Label trueCondition = new Label();
            falseCondition = new Label();
            injectMethodVisitor.loadThis();
            // 1st argument load BeanResolutionContext
            injectMethodVisitor.loadArg(0);
            // 2nd argument load BeanContext
            injectMethodVisitor.loadArg(1);
            // 3rd argument the field index
            injectMethodVisitor.push(currentFieldIndex);

            // invoke method containsValueForMethodArgument
            injectMethodVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(CONTAINS_VALUE_FOR_FIELD));
            injectMethodVisitor.push(false);

            injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, falseCondition);
            injectMethodVisitor.visitLabel(trueCondition);
        }

        if (!requiresReflection) {
            // if reflection is not required then set the field automatically within the body of the "injectBean" method

            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
            // first get the value of the field by calling AbstractBeanDefinition.getBeanForField(..)
            // load 'this'
            injectMethodVisitor.loadThis();
            // 1st argument load BeanResolutionContext
            injectMethodVisitor.visitVarInsn(ALOAD, 1);
            // 2nd argument load BeanContext
            injectMethodVisitor.visitVarInsn(ALOAD, 2);
            // 3rd argument the field index
            injectMethodVisitor.push(currentFieldIndex);
            // invoke getBeanForField
            pushInvokeMethodOnSuperClass(injectMethodVisitor, methodToInvoke);
            // cast the return value to the correct type
            pushCastToType(injectMethodVisitor, fieldElement.getType());

            injectMethodVisitor.visitFieldInsn(PUTFIELD, declaringTypeRef.getInternalName(), fieldElement.getName(), getTypeDescriptor(fieldElement.getType()));
        } else {
            // if reflection is required at reflective call
            pushInjectMethodForIndex(injectMethodVisitor, injectInstanceIndex, currentFieldIndex, "injectBeanField");
        }
        if (falseCondition != null) {
            injectMethodVisitor.visitLabel(falseCondition);
        }
        currentFieldIndex++;
    }

    private void pushAnnotationMetadata(AnnotationMetadata annotationMetadata, GeneratorAdapter constructorVisitor) {
        if (!(annotationMetadata instanceof DefaultAnnotationMetadata)) {
            constructorVisitor.visitInsn(ACONST_NULL);
        } else {
            AnnotationMetadataWriter.instantiateNewMetadata(
                    beanDefinitionType,
                    classWriter,
                    constructorVisitor,
                    (DefaultAnnotationMetadata) annotationMetadata,
                    loadTypeMethods
            );
        }
    }

    private void addInjectionPointForSetterInternal(
            AnnotationMetadata fieldAnnotationMetadata,
            boolean requiresReflection,
            String setterName,
            Map<String, ClassElement> genericTypes,
            Type declaringTypeRef) {

        // load 'this'
        GeneratorAdapter constructorVisitor = this.constructorVisitor;
        constructorVisitor.visitVarInsn(ALOAD, 0);


        // 1st argument: The declaring type
        constructorVisitor.push(declaringTypeRef);

        // 2nd argument: The method name
        constructorVisitor.push(setterName);

        // 3rd argument: the argument types
        pushTypeArgumentElements(
                beanDefinitionType,
                classWriter,
                constructorVisitor,
                declaringTypeRef.getClassName(),
                genericTypes,
                loadTypeMethods
        );

        // 4th argument: the annotation metadata
        if (fieldAnnotationMetadata == null || fieldAnnotationMetadata == AnnotationMetadata.EMPTY_METADATA) {
            constructorVisitor.visitInsn(ACONST_NULL);
        } else {
            AnnotationMetadataWriter.instantiateNewMetadata(
                    beanDefinitionType,
                    classWriter,
                    constructorVisitor,
                    (DefaultAnnotationMetadata) fieldAnnotationMetadata,
                    loadTypeMethods
            );
        }
        // 5th  argument to addInjectionPoint: do we need reflection?
        constructorVisitor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

        // invoke add injection point method
        pushInvokeMethodOnSuperClass(constructorVisitor, ADD_METHOD_INJECTION_POINT_METHOD);
    }

    /**
     * @param methodVisitData               The data for the method
     * @param constructorVisitor            The constructor visitor
     * @param injectMethodVisitor           The inject method visitor
     * @param injectInstanceIndex           The inject instance index
     * @param addMethodInjectionPointMethod The add method inject point method
     */
    private void visitMethodInjectionPointInternal(MethodVisitData methodVisitData,
                                                   GeneratorAdapter constructorVisitor,
                                                   GeneratorAdapter injectMethodVisitor,
                                                   int injectInstanceIndex,
                                                   Method addMethodInjectionPointMethod) {


        MethodElement methodElement = methodVisitData.getMethodElement();
        final AnnotationMetadata annotationMetadata = methodElement.getAnnotationMetadata();
        final List<ParameterElement> argumentTypes = Arrays.asList(methodElement.getParameters());
        final TypedElement declaringType = methodVisitData.beanType;
        final String methodName = methodElement.getName();
        final boolean requiresReflection = methodVisitData.requiresReflection;
        final ClassElement returnType = methodElement.getReturnType();
        DefaultAnnotationMetadata.contributeDefaults(this.annotationMetadata, annotationMetadata);
        boolean hasArguments = methodElement.hasParameters();
        int argCount = hasArguments ? argumentTypes.size() : 0;
        Type declaringTypeRef = JavaModelUtils.getTypeReference(declaringType);

        // load 'this'
        constructorVisitor.visitVarInsn(ALOAD, 0);

        // 1st argument: The declaring type
        constructorVisitor.push(declaringTypeRef);

        // 2nd argument: The method name
        constructorVisitor.push(methodName);

        // 3rd argument: the argument types
        if (hasArguments) {
            pushBuildArgumentsForMethod(
                    declaringType.getName(),
                    beanDefinitionType,
                    classWriter,
                    constructorVisitor,
                    argumentTypes,
                    loadTypeMethods
            );
            for (ParameterElement value : argumentTypes) {
                DefaultAnnotationMetadata.contributeDefaults(this.annotationMetadata, value.getAnnotationMetadata());
            }
        } else {
            constructorVisitor.visitInsn(ACONST_NULL);
        }

        // 4th argument: the annotation metadata
        pushAnnotationMetadata(this.annotationMetadata, constructorVisitor);

        // 5th  argument to addInjectionPoint: do we need reflection?
        constructorVisitor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);


        // invoke add injection point method
        pushInvokeMethodOnSuperClass(constructorVisitor, addMethodInjectionPointMethod);

        if (!requiresReflection) {
            // if the method doesn't require reflection then invoke it directly

            // invoke the method on this injected instance
            injectMethodVisitor.visitVarInsn(ALOAD, injectInstanceIndex);

            String methodDescriptor;
            if (hasArguments) {
                methodDescriptor = getMethodDescriptor(returnType, argumentTypes);
                Iterator<ParameterElement> argIterator = argumentTypes.iterator();
                for (int i = 0; i < argCount; i++) {
                    ParameterElement entry = argIterator.next();
                    AnnotationMetadata argMetadata = entry.getAnnotationMetadata();

                    // first get the value of the field by calling AbstractBeanDefinition.getBeanForMethod(..)
                    // load 'this'
                    injectMethodVisitor.visitVarInsn(ALOAD, 0);
                    // 1st argument load BeanResolutionContext
                    injectMethodVisitor.visitVarInsn(ALOAD, 1);
                    // 2nd argument load BeanContext
                    injectMethodVisitor.visitVarInsn(ALOAD, 2);
                    // 3rd argument the method index
                    injectMethodVisitor.push(currentMethodIndex);
                    // 4th argument the argument index
                    injectMethodVisitor.push(i);
                    // invoke getBeanForField

                    Method methodToInvoke = argMetadata.hasDeclaredStereotype(Value.class) || argMetadata.hasDeclaredStereotype(Property.class) ? GET_VALUE_FOR_METHOD_ARGUMENT : getInjectMethodForParameter(entry);
                    pushInvokeMethodOnSuperClass(injectMethodVisitor, methodToInvoke);
                    // cast the return value to the correct type
                    pushCastToType(injectMethodVisitor, entry);
                }
            } else {
                methodDescriptor = getMethodDescriptor(returnType, Collections.emptyList());
            }
            injectMethodVisitor.visitMethodInsn(isInterface ? INVOKEINTERFACE : INVOKEVIRTUAL,
                    declaringTypeRef.getInternalName(), methodName,
                    methodDescriptor, isInterface);
            if (isConfigurationProperties && returnType != PrimitiveElement.VOID) {
                injectMethodVisitor.pop();
            }
        } else {
            // otherwise use injectBeanMethod instead which triggers reflective injection
            pushInjectMethodForIndex(injectMethodVisitor, injectInstanceIndex, currentMethodIndex, "injectBeanMethod");
        }

        // increment the method index
        currentMethodIndex++;
    }

    private Method getInjectMethodForParameter(ParameterElement parameterElement) {
        final ClassElement genericType = parameterElement.getGenericType();
        Method methodToInvoke;
        if (genericType.isAssignable(Collection.class) || genericType.isArray()) {
            ClassElement typeArgument = genericType.isArray() ? genericType.fromArray() : genericType.getFirstTypeArgument().orElse(null);
            if (typeArgument != null) {
                if (typeArgument.isAssignable(BeanRegistration.class)) {
                    methodToInvoke = GET_BEAN_REGISTRATIONS_FOR_METHOD_ARGUMENT;
                } else {
                    methodToInvoke = GET_BEANS_OF_TYPE_FOR_METHOD_ARGUMENT;
                }
            } else {
                methodToInvoke = GET_BEAN_FOR_METHOD_ARGUMENT;
            }
        } else {
            if (genericType.isAssignable(BeanRegistration.class)) {
                methodToInvoke = GET_BEAN_REGISTRATION_FOR_METHOD_ARGUMENT;
            } else {
                methodToInvoke = GET_BEAN_FOR_METHOD_ARGUMENT;
            }
        }
        return methodToInvoke;
    }

    private void pushInvokeMethodOnSuperClass(MethodVisitor constructorVisitor, Method methodToInvoke) {
        constructorVisitor.visitMethodInsn(INVOKESPECIAL,
                isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION.getInternalName() : superType.getInternalName(),
                methodToInvoke.getName(),
                Type.getMethodDescriptor(methodToInvoke),
                false);
    }

    private void resolveBeanOrValueForSetter(Type declaringTypeRef, ClassElement returnType, String setterName, ClassElement fieldType, Method resolveMethod, boolean isValueOptional) {
        GeneratorAdapter injectVisitor = this.injectMethodVisitor;

        Label falseCondition = null;
        if (isValueOptional) {
            Label trueCondition = new Label();
            falseCondition = new Label();
            injectVisitor.loadThis();
            // 1st argument load BeanResolutionContext
            injectVisitor.loadArg(0);
            // 2nd argument load BeanContext
            injectVisitor.loadArg(1);
            // 3rd argument the field index
            injectVisitor.push(currentMethodIndex);
            // 4th argument the argument index
            injectVisitor.push(0);

            // invoke method containsValueForMethodArgument
            injectVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(CONTAINS_VALUE_FOR_METHOD_ARGUMENT));
            injectVisitor.push(false);

            injectVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, falseCondition);
            injectVisitor.visitLabel(trueCondition);
        }
        // invoke the method on this injected instance
        injectVisitor.visitVarInsn(ALOAD, injectInstanceIndex);
        String methodDescriptor = getMethodDescriptor(returnType, Collections.singletonList(fieldType));
        // first get the value of the field by calling AbstractBeanDefinition.getBeanForField(..)
        // load 'this'
        injectMethodVisitor.visitVarInsn(ALOAD, 0);
        // 1st argument load BeanResolutionContext
        injectMethodVisitor.visitVarInsn(ALOAD, 1);
        // 2nd argument load BeanContext
        injectMethodVisitor.visitVarInsn(ALOAD, 2);
        // 3rd argument the field index
        injectMethodVisitor.push(currentMethodIndex);
        // 4th argument the argument index
        // 5th argument is the default value
        injectVisitor.push(0);
        // invoke getBeanForField
        pushInvokeMethodOnSuperClass(injectVisitor, resolveMethod);
        // cast the return value to the correct type
        pushCastToType(injectVisitor, fieldType);
        injectVisitor.visitMethodInsn(INVOKEVIRTUAL,
                declaringTypeRef.getInternalName(), setterName,
                methodDescriptor, false);
        if (returnType != PrimitiveElement.VOID) {
            injectVisitor.pop();
        }
        if (falseCondition != null) {
            injectVisitor.visitLabel(falseCondition);
        }
    }

    @SuppressWarnings("MagicNumber")
    private void visitInjectMethodDefinition() {
        if (injectMethodVisitor == null) {
            String desc = getMethodDescriptor(Object.class.getName(), BeanResolutionContext.class.getName(), BeanContext.class.getName(), Object.class.getName());
            injectMethodVisitor = new GeneratorAdapter(classWriter.visitMethod(
                    ACC_PROTECTED,
                    "injectBean",
                    desc,
                    null,
                    null), ACC_PROTECTED, "injectBean", desc);

            GeneratorAdapter injectMethodVisitor = this.injectMethodVisitor;
            if (isConfigurationProperties) {
                injectMethodVisitor.loadThis();
                injectMethodVisitor.loadArg(0); // the resolution context
                injectMethodVisitor.loadArg(1); // the bean context
                // invoke AbstractBeanDefinition.containsProperties(..)
                injectMethodVisitor.invokeVirtual(beanDefinitionType, org.objectweb.asm.commons.Method.getMethod(CONTAINS_PROPERTIES_METHOD));
                injectMethodVisitor.push(false);
                injectEnd = new Label();
                injectMethodVisitor.ifCmp(Type.BOOLEAN_TYPE, GeneratorAdapter.EQ, injectEnd);
                // add the true condition
                injectMethodVisitor.visitLabel(new Label());
            }
            // The object being injected is argument 3 of the inject method
            injectMethodVisitor.visitVarInsn(ALOAD, 3);
            // store it in a local variable
            injectMethodVisitor.visitTypeInsn(CHECKCAST, beanType.getInternalName());
            injectInstanceIndex = pushNewInjectLocalVariable();
            injectMethodVisitor.visitInsn(ACONST_NULL);
            optionalInstanceIndex = pushNewInjectLocalVariable();
        }
    }

    @SuppressWarnings("MagicNumber")
    private void visitPostConstructMethodDefinition(boolean intercepted) {
        if (postConstructMethodVisitor == null) {
            interfaceTypes.add(InitializingBeanDefinition.class);

            // override the post construct method
            final String lifeCycleMethodName = "initialize";
            GeneratorAdapter postConstructMethodVisitor = newLifeCycleMethod(lifeCycleMethodName);

            this.postConstructMethodVisitor = postConstructMethodVisitor;
            // The object being injected is argument 3 of the inject method
            postConstructMethodVisitor.visitVarInsn(ALOAD, 3);
            // store it in a local variable
            postConstructMethodVisitor.visitTypeInsn(CHECKCAST, beanType.getInternalName());
            postConstructInstanceIndex = pushNewPostConstructLocalVariable();

            invokeSuperInjectMethod(postConstructMethodVisitor, POST_CONSTRUCT_METHOD);

            if (intercepted) {
                // store executable method in local variable
                final int postConstructorMethodVar = pushNewBuildLocalVariable();
                writeInterceptedLifecycleMethod(
                        lifeCycleMethodName,
                        lifeCycleMethodName,
                        buildMethodVisitor,
                        buildInstanceIndex,
                        postConstructorMethodVar
                );
            } else {
                pushBeanDefinitionMethodInvocation(buildMethodVisitor, lifeCycleMethodName);
            }
            pushCastToType(buildMethodVisitor, beanType);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);

        }
    }

    private void writeInterceptedLifecycleMethod(
            String lifeCycleMethodName,
            String dispatchMethodName,
            GeneratorAdapter targetMethodVisitor,
            int instanceIndex,
            int executableInstanceIndex) {
        // if there is method interception in place we need to construct an inner executable method class that invokes the "initialize"
        // method and apply interception
        final InnerClassDef postConstructInnerMethod = newInnerClass(AbstractExecutableMethod.class);
        // needs fields to propagate the correct arguments to the initialize method
        final ClassWriter postConstructInnerWriter = postConstructInnerMethod.innerClassWriter;
        final Type postConstructInnerClassType = postConstructInnerMethod.innerClassType;
        final String fieldBeanDef = "$beanDef";
        final String fieldResContext = "$resolutionContext";
        final String fieldBeanContext = "$beanContext";
        final String fieldBean = "$bean";
        newFinalField(postConstructInnerWriter, beanDefinitionType, fieldBeanDef);
        newFinalField(postConstructInnerWriter, TYPE_RESOLUTION_CONTEXT, fieldResContext);
        newFinalField(postConstructInnerWriter, TYPE_BEAN_CONTEXT, fieldBeanContext);
        newFinalField(postConstructInnerWriter, beanType, fieldBean);
        // constructor will be AbstractExecutableMethod(BeanDefinition, BeanResolutionContext, BeanContext, T beanType)
        final String constructorDescriptor = getConstructorDescriptor(new Type[]{
            beanDefinitionType,
            TYPE_RESOLUTION_CONTEXT,
            TYPE_BEAN_CONTEXT,
            beanType
        });
        GeneratorAdapter protectedConstructor = new GeneratorAdapter(
                postConstructInnerWriter.visitMethod(
                        ACC_PROTECTED, CONSTRUCTOR_NAME,
                        constructorDescriptor,
                        null,
                        null
                ),
                ACC_PROTECTED,
                CONSTRUCTOR_NAME,
                constructorDescriptor
        );
        // set field $beanDef
        protectedConstructor.loadThis();
        protectedConstructor.visitVarInsn(ALOAD, 1);
        protectedConstructor.putField(postConstructInnerClassType, fieldBeanDef, beanDefinitionType);
        // set field $resolutionContext
        protectedConstructor.loadThis();
        protectedConstructor.visitVarInsn(ALOAD, 2);
        protectedConstructor.putField(postConstructInnerClassType, fieldResContext, TYPE_RESOLUTION_CONTEXT);
        // set field $beanContext
        protectedConstructor.loadThis();
        protectedConstructor.visitVarInsn(ALOAD, 3);
        protectedConstructor.putField(postConstructInnerClassType, fieldBeanContext, TYPE_BEAN_CONTEXT);
        // set field $bean
        protectedConstructor.loadThis();
        protectedConstructor.visitVarInsn(ALOAD, 4);
        protectedConstructor.putField(postConstructInnerClassType, fieldBean, beanType);

        protectedConstructor.loadThis();
        protectedConstructor.push(beanType);
        protectedConstructor.push(lifeCycleMethodName);
        invokeConstructor(
                protectedConstructor,
                AbstractExecutableMethod.class,
                Class.class,
                String.class
        );
        protectedConstructor.returnValue();
        protectedConstructor.visitMaxs(1, 1);
        protectedConstructor.visitEnd();

        // annotation metadata should reference to the metadata for bean definition
        final GeneratorAdapter getAnnotationMetadata = startPublicFinalMethodZeroArgs(postConstructInnerWriter, AnnotationMetadata.class, "getAnnotationMetadata");
        lookupReferenceAnnotationMetadata(getAnnotationMetadata);

        // now define the invokerInternal method
        final GeneratorAdapter invokeMethod = startPublicMethod(postConstructInnerWriter, ExecutableMethodWriter.METHOD_INVOKE_INTERNAL);
        invokeMethod.loadThis();
        // load the bean definition field
        invokeMethod.getField(postConstructInnerClassType, fieldBeanDef, beanDefinitionType);
        // load the arguments to the initialize method
        // 1st argument the resolution context
        invokeMethod.loadThis();
        invokeMethod.getField(postConstructInnerClassType, fieldResContext, TYPE_RESOLUTION_CONTEXT);
        // 2nd argument the bean context
        invokeMethod.loadThis();
        invokeMethod.getField(postConstructInnerClassType, fieldBeanContext, TYPE_BEAN_CONTEXT);
        // 3rd argument the bean
        invokeMethod.loadThis();
        invokeMethod.getField(postConstructInnerClassType, fieldBean, beanType);
        // now invoke initialize
        invokeMethod.visitMethodInsn(INVOKEVIRTUAL,
                beanDefinitionInternalName,
                lifeCycleMethodName,
                METHOD_DESCRIPTOR_INITIALIZE,
                false);
        invokeMethod.returnValue();
        invokeMethod.visitMaxs(1, 1);
        invokeMethod.visitEnd();

        // now instantiate the inner class
        targetMethodVisitor.visitTypeInsn(NEW, postConstructInnerMethod.constructorInternalName);
        targetMethodVisitor.visitInsn(DUP);
        // constructor signature is AbstractExecutableMethod(BeanDefinition, BeanResolutionContext, BeanContext, T beanType)
        // 1st argument: pass outer class instance to constructor
        targetMethodVisitor.loadThis();

        // 2nd argument: resolution context
        targetMethodVisitor.visitVarInsn(ALOAD, 1);

        // 3rd argument: bean context
        targetMethodVisitor.visitVarInsn(ALOAD, 2);

        // 4th argument: bean instance
        targetMethodVisitor.visitVarInsn(ALOAD, instanceIndex);
        pushCastToType(targetMethodVisitor, beanType);
        targetMethodVisitor.visitMethodInsn(
                INVOKESPECIAL,
                postConstructInnerMethod.constructorInternalName,
                "<init>",
                constructorDescriptor,
                false
        );
        targetMethodVisitor.visitVarInsn(ASTORE, executableInstanceIndex);
        // now invoke MethodInterceptorChain.initialize or dispose
        // 1st argument: resolution context
        targetMethodVisitor.visitVarInsn(ALOAD, 1);
        // 2nd argument: bean context
        targetMethodVisitor.visitVarInsn(ALOAD, 2);
        // 3rd argument: this definition
        targetMethodVisitor.loadThis();
        // 4th argument: executable method instance
        targetMethodVisitor.visitVarInsn(ALOAD, executableInstanceIndex);
        // 5th argument: the bean instance
        targetMethodVisitor.visitVarInsn(ALOAD, instanceIndex);
        pushCastToType(targetMethodVisitor, beanType);
        targetMethodVisitor.visitMethodInsn(
                INVOKESTATIC,
                "io/micronaut/aop/chain/MethodInterceptorChain",
                dispatchMethodName,
                METHOD_DESCRIPTOR_INTERCEPTED_LIFECYCLE,
                false
        );
        targetMethodVisitor.visitVarInsn(ALOAD, instanceIndex);
    }

    private void pushInjectMethodForIndex(GeneratorAdapter methodVisitor, int instanceIndex, int injectIndex, String injectMethodName) {
        Method injectBeanMethod = ReflectionUtils.getRequiredMethod(AbstractBeanDefinition.class, injectMethodName, BeanResolutionContext.class, DefaultBeanContext.class, int.class, Object.class);
        // load 'this'
        methodVisitor.visitVarInsn(ALOAD, 0);
        // 1st argument load BeanResolutionContext
        methodVisitor.visitVarInsn(ALOAD, 1);
        // 2nd argument load BeanContext
        methodVisitor.visitVarInsn(ALOAD, 2);
        pushCastToType(methodVisitor, DefaultBeanContext.class);
        // 3rd argument the method index
        methodVisitor.push(injectIndex);
        // 4th argument: the instance being injected
        methodVisitor.visitVarInsn(ALOAD, instanceIndex);

        pushInvokeMethodOnSuperClass(methodVisitor, injectBeanMethod);
    }

    @SuppressWarnings("MagicNumber")
    private void visitPreDestroyMethodDefinition(boolean intercepted) {
        if (preDestroyMethodVisitor == null) {
            interfaceTypes.add(DisposableBeanDefinition.class);

            // override the dispose method
            GeneratorAdapter preDestroyMethodVisitor;
            if (intercepted) {
                preDestroyMethodVisitor = newLifeCycleMethod("doDispose");

                final GeneratorAdapter disposeMethod = newLifeCycleMethod("dispose");
                writeInterceptedLifecycleMethod(
                        "doDispose",
                        "dispose",
                        disposeMethod,
                        3,
                        4
                );
                disposeMethod.returnValue();


                this.interceptedDisposeMethod = disposeMethod;
            } else {
                preDestroyMethodVisitor = newLifeCycleMethod("dispose");
            }

            this.preDestroyMethodVisitor = preDestroyMethodVisitor;
            // The object being injected is argument 3 of the inject method
            preDestroyMethodVisitor.visitVarInsn(ALOAD, 3);
            // store it in a local variable
            preDestroyMethodVisitor.visitTypeInsn(CHECKCAST, beanType.getInternalName());
            preDestroyInstanceIndex = pushNewPreDestroyLocalVariable();

            invokeSuperInjectMethod(preDestroyMethodVisitor, PRE_DESTROY_METHOD);
        }
    }

    private GeneratorAdapter newLifeCycleMethod(String methodName) {
        String desc = getMethodDescriptor(Object.class.getName(), BeanResolutionContext.class.getName(), BeanContext.class.getName(), Object.class.getName());
        return new GeneratorAdapter(classWriter.visitMethod(
                ACC_PUBLIC,
                methodName,
                desc,
                getMethodSignature(getTypeDescriptor(providedBeanClassName), getTypeDescriptor(BeanResolutionContext.class.getName()), getTypeDescriptor(BeanContext.class.getName()), getTypeDescriptor(providedBeanClassName)),
                null),
                ACC_PUBLIC,
                methodName,
                desc
        );
    }

    private void finalizeBuildMethod() {
        // if this is a provided bean then execute "get"
        if (!providedBeanClassName.equals(beanFullClassName)) {

            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);
            buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                    beanType.getInternalName(),
                    "get",
                    Type.getMethodDescriptor(Type.getType(Object.class)),
                    false);
            pushCastToType(buildMethodVisitor, providedType);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "injectAnother");
            pushCastToType(buildMethodVisitor, providedType);
        }
    }

    private void finalizeInjectMethod() {
        if (injectEnd != null) {
            injectMethodVisitor.visitLabel(injectEnd);
        }

        invokeSuperInjectMethod(injectMethodVisitor, INJECT_BEAN_METHOD);
        injectMethodVisitor.visitInsn(ARETURN);
    }

    @SuppressWarnings("MagicNumber")
    private void invokeSuperInjectMethod(MethodVisitor methodVisitor, Method methodToInvoke) {
        // load this
        methodVisitor.visitVarInsn(ALOAD, 0);
        // load BeanResolutionContext arg 1
        methodVisitor.visitVarInsn(ALOAD, 1);
        // load BeanContext arg 2
        methodVisitor.visitVarInsn(ALOAD, 2);
        pushCastToType(methodVisitor, DefaultBeanContext.class);

        // load object being inject arg 3
        methodVisitor.visitVarInsn(ALOAD, 3);
        pushInvokeMethodOnSuperClass(methodVisitor, methodToInvoke);
    }

    private void visitBuildFactoryMethodDefinition(
            ClassElement factoryClass,
            Element factoryMethod) {
        if (buildMethodVisitor == null) {
            ParameterElement[] parameters;

            if (factoryMethod instanceof MethodElement) {
                parameters = ((MethodElement) factoryMethod).getParameters();
            } else {
                parameters = new ParameterElement[0];
            }

            List<ParameterElement> parameterList = Arrays.asList(parameters);
            boolean isParametrized = isParametrized(parameters);
            boolean isIntercepted = isConstructorIntercepted(factoryMethod);
            Type factoryType = JavaModelUtils.getTypeReference(factoryClass);

            defineBuilderMethod(isParametrized);
            // load this

            GeneratorAdapter buildMethodVisitor = this.buildMethodVisitor;
            // for Factory beans first we need to lookup the the factory bean
            // before invoking the method to instantiate
            // the below code looks up the factory bean.

            // Load the BeanContext for the method call
            buildMethodVisitor.visitVarInsn(ALOAD, 2);
            pushCastToType(buildMethodVisitor, DefaultBeanContext.class);
            // load the first argument of the method (the BeanResolutionContext) to be passed to the method
            buildMethodVisitor.visitVarInsn(ALOAD, 1);
            // second argument is the bean type
            buildMethodVisitor.push(factoryType);
            buildMethodVisitor.invokeVirtual(
                    Type.getType(DefaultBeanContext.class),
                    org.objectweb.asm.commons.Method.getMethod(METHOD_GET_BEAN)
            );

            // store a reference to the bean being built at index 3
            int factoryVar = pushNewBuildLocalVariable();
            buildMethodVisitor.visitVarInsn(ALOAD, factoryVar);
            pushCastToType(buildMethodVisitor, factoryClass);
            String methodDescriptor = getMethodDescriptorForReturnType(beanType, parameterList);
            if (isIntercepted) {
                initInterceptedConstructorWriter(
                        buildMethodVisitor,
                        parameterList,
                        new FactoryMethodDef(factoryType, factoryMethod, methodDescriptor, factoryVar)
                );
                final int constructorIndex = pushNewBuildLocalVariable();
                // populate an Object[] of all constructor arguments
                final int parametersIndex = createParameterArray(parameterList, buildMethodVisitor);
                invokeConstructorChain(buildMethodVisitor, constructorIndex, parametersIndex, parameterList);
            } else {

                if (!parameterList.isEmpty()) {
                    pushConstructorArguments(buildMethodVisitor, parameters);
                }
                if (factoryMethod instanceof MethodElement) {
                    buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                            factoryType.getInternalName(),
                            factoryMethod.getName(),
                            methodDescriptor, false);
                } else {
                    buildMethodVisitor.getField(
                            factoryType,
                            factoryMethod.getName(),
                            beanType
                    );
                }
            }


            this.buildInstanceIndex = pushNewBuildLocalVariable();
            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "injectBean");
            pushCastToType(buildMethodVisitor, beanType);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);
            initLifeCycleMethodsIfNecessary();
        }
    }

    private void visitBuildMethodDefinition(MethodElement constructor) {
        if (buildMethodVisitor == null) {
            boolean isIntercepted = isConstructorIntercepted(constructor);
            final ParameterElement[] parameterArray = constructor.getParameters();
            List<ParameterElement> parameters = Arrays.asList(parameterArray);
            boolean isParametrized = isParametrized(parameterArray);
            defineBuilderMethod(isParametrized);
            // load this

            GeneratorAdapter buildMethodVisitor = this.buildMethodVisitor;

            // if there is constructor interception present then we have to
            // build the parameters into an Object[] and build a constructor invocation
            if (isIntercepted) {
                initInterceptedConstructorWriter(buildMethodVisitor, parameters, null);
                final int constructorIndex = pushNewBuildLocalVariable();
                // populate an Object[] of all constructor arguments
                final int parametersIndex = createParameterArray(parameters, buildMethodVisitor);
                invokeConstructorChain(buildMethodVisitor, constructorIndex, parametersIndex, parameters);
            } else {
                buildMethodVisitor.visitTypeInsn(NEW, beanType.getInternalName());
                buildMethodVisitor.visitInsn(DUP);
                pushConstructorArguments(buildMethodVisitor, parameterArray);
                String constructorDescriptor = getConstructorDescriptor(parameters);
                buildMethodVisitor.visitMethodInsn(INVOKESPECIAL, beanType.getInternalName(), "<init>", constructorDescriptor, false);
            }

            // store a reference to the bean being built at index 3
            this.buildInstanceIndex = pushNewBuildLocalVariable();
            pushBeanDefinitionMethodInvocation(buildMethodVisitor, "injectBean");
            pushCastToType(buildMethodVisitor, beanType);
            buildMethodVisitor.visitVarInsn(ASTORE, buildInstanceIndex);
            buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);
            initLifeCycleMethodsIfNecessary();
        }
    }

    private void initLifeCycleMethodsIfNecessary() {
        if (isInterceptedLifeCycleByType(this.annotationMetadata, "POST_CONSTRUCT")) {
            visitPostConstructMethodDefinition(true);
        }
        if (isInterceptedLifeCycleByType(this.annotationMetadata, "PRE_DESTROY")) {
            visitPreDestroyMethodDefinition(true);
        }
    }

    private void invokeConstructorChain(GeneratorAdapter generatorAdapter, int constructorIndex, int parametersIndex, List<ParameterElement> parameters) {
        // 1st argument: The resolution context
        generatorAdapter.visitVarInsn(ALOAD, 1);
        // 2nd argument: The bean context
        generatorAdapter.visitVarInsn(ALOAD, 2);
        // 3rd argument: The interceptors if present
        if (StringUtils.isNotEmpty(interceptedType)) {
            // interceptors will be last entry in parameter list for interceptors types
            generatorAdapter.visitVarInsn(ALOAD, parametersIndex);
            // array index for last parameter
            generatorAdapter.push(parameters.size() - 1);
            generatorAdapter.arrayLoad(TYPE_OBJECT);
            pushCastToType(generatorAdapter, List.class);
        } else {
            // for non interceptor types we have to perform a lookup based on the binding
            generatorAdapter.visitInsn(ACONST_NULL);
        }
        // 4th argument: the bean definition
        generatorAdapter.loadThis();
        // 5th argument: The constructor
        generatorAdapter.visitVarInsn(ALOAD, constructorIndex);
        // 6th argument:  load the Object[] for the parameters
        generatorAdapter.visitVarInsn(ALOAD, parametersIndex);

        generatorAdapter.visitMethodInsn(
                INVOKESTATIC,
                "io/micronaut/aop/chain/ConstructorInterceptorChain",
                "instantiate",
                METHOD_DESCRIPTOR_CONSTRUCTOR_INSTANTIATE,
                false
        );
    }

    private void initInterceptedConstructorWriter(
            GeneratorAdapter buildMethodVisitor,
            List<ParameterElement> parameters,
            @Nullable FactoryMethodDef factoryMethodDef) {
        // write the constructor that is a subclass of AbstractConstructorInjectionPoint
        InnerClassDef constructorInjectionPointInnerClass = newInnerClass(AbstractConstructorInjectionPoint.class);
        final ClassWriter interceptedConstructorWriter = constructorInjectionPointInnerClass.innerClassWriter;
        org.objectweb.asm.commons.Method constructorMethod = org.objectweb.asm.commons.Method.getMethod(CONSTRUCTOR_ABSTRACT_CONSTRUCTOR_IP);
        GeneratorAdapter protectedConstructor;

        final boolean hasFactoryMethod = factoryMethodDef != null;
        final String interceptedConstructorDescriptor;
        final Type factoryType = hasFactoryMethod ? factoryMethodDef.factoryType : null;
        final String factoryFieldName = "$factory";
        if (hasFactoryMethod) {
            // for factory methods we have to store the factory instance in a field and modify the constructor pass the factory instance
            newFinalField(interceptedConstructorWriter, factoryType, factoryFieldName);

            interceptedConstructorDescriptor = getConstructorDescriptor(new Type[] {
                    TYPE_BEAN_DEFINITION,
                    factoryType
            });
            protectedConstructor = new GeneratorAdapter(
                    interceptedConstructorWriter.visitMethod(
                            ACC_PROTECTED, CONSTRUCTOR_NAME,
                            interceptedConstructorDescriptor,
                            null,
                            null
                    ),
                    ACC_PROTECTED,
                    CONSTRUCTOR_NAME,
                    interceptedConstructorDescriptor
            );
        } else {
            interceptedConstructorDescriptor = constructorMethod.getDescriptor();
            protectedConstructor = new GeneratorAdapter(
                    interceptedConstructorWriter.visitMethod(
                            ACC_PROTECTED, CONSTRUCTOR_NAME,
                            interceptedConstructorDescriptor,
                            null,
                            null
                    ),
                    ACC_PROTECTED,
                    CONSTRUCTOR_NAME,
                    interceptedConstructorDescriptor
            );

        }
        if (hasFactoryMethod) {
            protectedConstructor.loadThis();
            protectedConstructor.loadArg(1);
            protectedConstructor.putField(constructorInjectionPointInnerClass.innerClassType, factoryFieldName, factoryType);
        }
        protectedConstructor.loadThis();
        protectedConstructor.loadArg(0);
        protectedConstructor.invokeConstructor(Type.getType(AbstractConstructorInjectionPoint.class), constructorMethod);
        protectedConstructor.returnValue();
        protectedConstructor.visitMaxs(1, 1);
        protectedConstructor.visitEnd();

        // now we need to implement the invoke method to execute the actual instantiation
        final GeneratorAdapter invokeMethod = startPublicMethod(interceptedConstructorWriter, METHOD_INVOKE_CONSTRUCTOR);
        if (hasFactoryMethod) {
            invokeMethod.loadThis();
            invokeMethod.getField(
                    constructorInjectionPointInnerClass.innerClassType,
                    factoryFieldName,
                    factoryType
            );
            pushCastToType(invokeMethod, factoryType);
        } else {
            invokeMethod.visitTypeInsn(NEW, beanType.getInternalName());
            invokeMethod.visitInsn(DUP);
        }
        for (int i = 0; i < parameters.size(); i++) {
            invokeMethod.loadArg(0);
            invokeMethod.push(i);
            invokeMethod.arrayLoad(TYPE_OBJECT);
            pushCastToType(invokeMethod, parameters.get(i));
        }

        if (hasFactoryMethod) {
            if (factoryMethodDef.factoryMethod instanceof MethodElement) {

                invokeMethod.visitMethodInsn(
                        INVOKEVIRTUAL,
                        factoryType.getInternalName(),
                        factoryMethodDef.factoryMethod.getName(),
                        factoryMethodDef.methodDescriptor,
                        false
                );
            } else {
                invokeMethod.getField(factoryType, factoryMethodDef.factoryMethod.getName(), beanType);
            }
        } else {
            String constructorDescriptor = getConstructorDescriptor(parameters);
            invokeMethod.visitMethodInsn(INVOKESPECIAL, beanType.getInternalName(), "<init>", constructorDescriptor, false);
        }
        invokeMethod.returnValue();
        invokeMethod.visitMaxs(1, 1);
        invokeMethod.visitEnd();

        // instantiate a new instance and return
        buildMethodVisitor.visitTypeInsn(NEW, constructorInjectionPointInnerClass.constructorInternalName);
        buildMethodVisitor.visitInsn(DUP);
        // pass outer class instance to constructor
        buildMethodVisitor.loadThis();

        if (hasFactoryMethod) {
            buildMethodVisitor.visitVarInsn(ALOAD, factoryMethodDef.factoryVar);
            pushCastToType(buildMethodVisitor, factoryType);
        }

        buildMethodVisitor.visitMethodInsn(
                INVOKESPECIAL,
                constructorInjectionPointInnerClass.constructorInternalName,
                "<init>",
                interceptedConstructorDescriptor,
                false
        );
    }

    private void newFinalField(ClassWriter classWriter, Type fieldType, String fieldName) {
        classWriter
                .visitField(ACC_PRIVATE | ACC_FINAL,
                        fieldName,
                        fieldType.getDescriptor(),
                        null,
                        null
                );
    }

    private InnerClassDef newInnerClass(Class<?> superType) {
        ClassWriter interceptedConstructorWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        String interceptedConstructorWriterName = newInnerClassName();
        this.innerClasses.put(interceptedConstructorWriterName, interceptedConstructorWriter);
        final String constructorInternalName = getInternalName(interceptedConstructorWriterName);
        final Type interceptedConstructorType = getTypeReferenceForName(interceptedConstructorWriterName);
        interceptedConstructorWriter.visit(V1_8, ACC_SYNTHETIC | ACC_FINAL | ACC_PRIVATE,
                constructorInternalName,
                null,
                Type.getInternalName(superType),
                null
        );

        interceptedConstructorWriter.visitAnnotation(TYPE_GENERATED.getDescriptor(), false);
        interceptedConstructorWriter.visitOuterClass(
                beanDefinitionInternalName,
                null,
                null
        );
        classWriter.visitInnerClass(constructorInternalName, beanDefinitionInternalName, null, ACC_PRIVATE);
        return new InnerClassDef(
            interceptedConstructorWriterName,
            interceptedConstructorWriter,
            constructorInternalName,
            interceptedConstructorType
        );
    }

    @NotNull
    private String newInnerClassName() {
        return this.beanDefinitionName + "$" + ++innerClassIndex;
    }

    private int createParameterArray(List<ParameterElement> parameters, GeneratorAdapter buildMethodVisitor) {
        final int pLen = parameters.size();
        pushNewArray(buildMethodVisitor, Object.class, pLen);
        for (int i = 0; i < pLen; i++) {
            final ParameterElement parameter = parameters.get(i);
            int parameterIndex = i;
            pushStoreInArray(buildMethodVisitor, i, pLen, () ->
                    pushConstructorArgument(
                            buildMethodVisitor,
                            parameter.getName(),
                            parameter,
                            parameter.getAnnotationMetadata(),
                            parameterIndex
                    )
            );
        }
        return pushNewBuildLocalVariable();
    }

    private boolean isConstructorIntercepted(Element constructor) {
        // a constructor is intercepted when this bean is an advised type but not proxied
        // and any AROUND_CONSTRUCT annotations are present
        AnnotationMetadataHierarchy annotationMetadata = new AnnotationMetadataHierarchy(this.annotationMetadata, constructor.getAnnotationMetadata());
        final String interceptType = "AROUND_CONSTRUCT";
        // for beans that are @Around(proxyTarget=true) only the constructor of the proxy target should be intercepted. Beans returned from factories are always proxyTarget=true

        return isInterceptedLifeCycleByType(annotationMetadata, interceptType);
    }

    private boolean isInterceptedLifeCycleByType(AnnotationMetadata annotationMetadata, String interceptType) {
        if (this.beanTypeElement.isAssignable("io.micronaut.aop.Interceptor")) {
            // interceptor beans cannot have lifecycle methods intercepted
            return false;
        }
        final Element originatingElement = getOriginatingElements()[0];
        final boolean isFactoryMethod = (originatingElement instanceof MethodElement && !(originatingElement instanceof ConstructorElement));
        final boolean isProxyTarget = annotationMetadata.booleanValue(AnnotationUtil.ANN_AROUND, "proxyTarget").orElse(false) || isFactoryMethod;
        // for beans that are @Around(proxyTarget=false) only the generated AOP impl should be intercepted
        final boolean isAopType = StringUtils.isNotEmpty(interceptedType);
        final boolean isConstructorInterceptionCandidate = (isProxyTarget && !isAopType) || (isAopType && !isProxyTarget);
        final boolean hasAroundConstruct;
        final io.micronaut.core.annotation.AnnotationValue<Annotation> interceptorBindings
                = annotationMetadata.getAnnotation(AnnotationUtil.ANN_INTERCEPTOR_BINDINGS);
        final List<AnnotationValue<Annotation>> interceptorBindingAnnotations;
        if (interceptorBindings != null) {
            interceptorBindingAnnotations = interceptorBindings.getAnnotations(AnnotationMetadata.VALUE_MEMBER);
            hasAroundConstruct = interceptorBindingAnnotations
                    .stream()
                    .anyMatch(av -> av.stringValue("kind").map(k -> k.equals(interceptType)).orElse(false));
        } else {
            interceptorBindingAnnotations = Collections.emptyList();
            hasAroundConstruct = false;
        }

        if (isConstructorInterceptionCandidate) {
            return hasAroundConstruct;
        } else if (hasAroundConstruct) {
            // if no other AOP advice is applied
            return interceptorBindingAnnotations
                    .stream()
                    .noneMatch(av -> av.stringValue("kind").map(k -> k.equals("AROUND")).orElse(false));
        } else {
            return false;
        }
    }

    private void pushConstructorArguments(GeneratorAdapter buildMethodVisitor,
                                          ParameterElement[] parameters) {
        int size = parameters.length;
        if (size > 0) {
            for (int i = 0; i < parameters.length; i++) {
                ParameterElement parameter = parameters[i];
                pushConstructorArgument(buildMethodVisitor, parameter.getName(), parameter, parameter.getAnnotationMetadata(), i);
            }
        }
    }

    private void pushConstructorArgument(GeneratorAdapter buildMethodVisitor,
                                         String argumentName,
                                         ParameterElement argumentType,
                                         AnnotationMetadata annotationMetadata,
                                         int index) {
        if (isAnnotatedWithParameter(annotationMetadata) && argsIndex > -1) {
            // load the args
            buildMethodVisitor.visitVarInsn(ALOAD, argsIndex);
            // the argument name
            buildMethodVisitor.push(argumentName);
            buildMethodVisitor.invokeInterface(Type.getType(Map.class), org.objectweb.asm.commons.Method.getMethod(ReflectionUtils.getRequiredMethod(Map.class, "get", Object.class)));
            pushCastToType(buildMethodVisitor, argumentType);
        } else {
            // Load this for method call
            buildMethodVisitor.visitVarInsn(ALOAD, 0);

            // load the first two arguments of the method (the BeanResolutionContext and the BeanContext) to be passed to the method
            buildMethodVisitor.visitVarInsn(ALOAD, 1);
            buildMethodVisitor.visitVarInsn(ALOAD, 2);
            // pass the index of the method as the third argument
            buildMethodVisitor.push(index);
            // invoke the getBeanForConstructorArgument method
            Method methodToInvoke;

            if (isValueType(annotationMetadata)) {
                methodToInvoke = GET_VALUE_FOR_CONSTRUCTOR_ARGUMENT;
            } else {
                final ClassElement genericType = argumentType.getGenericType();
                if (genericType.isAssignable(Collection.class) || genericType.isArray()) {
                    ClassElement typeArgument = genericType.isArray() ? genericType.fromArray() : genericType.getFirstTypeArgument().orElse(null);
                    if (typeArgument != null) {
                        if (typeArgument.isAssignable(BeanRegistration.class)) {
                            methodToInvoke = GET_BEAN_REGISTRATIONS_FOR_CONSTRUCTOR_ARGUMENT;
                        } else {
                            methodToInvoke = GET_BEANS_OF_TYPE_FOR_CONSTRUCTOR_ARGUMENT;
                        }
                    } else {
                        methodToInvoke = GET_BEAN_FOR_CONSTRUCTOR_ARGUMENT;
                    }
                } else {
                    if (genericType.isAssignable(BeanRegistration.class)) {
                        methodToInvoke = GET_BEAN_REGISTRATION_FOR_CONSTRUCTOR_ARGUMENT;
                    } else {
                        methodToInvoke = GET_BEAN_FOR_CONSTRUCTOR_ARGUMENT;
                    }
                }
            }
            pushInvokeMethodOnSuperClass(buildMethodVisitor, methodToInvoke);
            pushCastToType(buildMethodVisitor, argumentType);
        }
    }

    private boolean isValueType(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata != null) {
            return annotationMetadata.hasDeclaredStereotype(Value.class) || annotationMetadata.hasDeclaredStereotype(Property.class);
        }
        return false;
    }

    private boolean isAnnotatedWithParameter(AnnotationMetadata annotationMetadata) {
        if (annotationMetadata != null) {
            return annotationMetadata.hasDeclaredAnnotation(Parameter.class);
        }
        return false;
    }

    private boolean isParametrized(ParameterElement...parameters) {
        return Arrays.stream(parameters).anyMatch(p -> isAnnotatedWithParameter(p.getAnnotationMetadata()));
    }

    private void defineBuilderMethod(boolean isParametrized) {
        if (isParametrized) {
            superType = TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION;
            argsIndex = buildMethodLocalCount++;
        }

        String methodDescriptor;
        String methodSignature;
        if (isParametrized) {
            methodDescriptor = getMethodDescriptor(
                    Object.class.getName(),
                    BeanResolutionContext.class.getName(),
                    BeanContext.class.getName(),
                    BeanDefinition.class.getName(),
                    Map.class.getName()
            );
            methodSignature = getMethodSignature(
                    getTypeDescriptor(providedBeanClassName),
                    getTypeDescriptor(BeanResolutionContext.class.getName()),
                    getTypeDescriptor(BeanContext.class.getName()),
                    getTypeDescriptor(BeanDefinition.class.getName(),
                            providedBeanClassName),
                    getTypeDescriptor(Map.class.getName())
            );
        } else {
            methodDescriptor = getMethodDescriptor(
                    Object.class.getName(),
                    BeanResolutionContext.class.getName(),
                    BeanContext.class.getName(),
                    BeanDefinition.class.getName()
            );
            methodSignature = getMethodSignature(
                    getTypeDescriptor(providedBeanClassName),
                    getTypeDescriptor(BeanResolutionContext.class.getName()),
                    getTypeDescriptor(BeanContext.class.getName()),
                    getTypeDescriptor(BeanDefinition.class.getName(),
                            providedBeanClassName)
            );
        }

        String methodName = isParametrized ? "doBuild" : "build";
        this.buildMethodVisitor = new GeneratorAdapter(classWriter.visitMethod(
                ACC_PUBLIC,
                methodName,
                methodDescriptor,
                methodSignature,
                null), ACC_PUBLIC, methodName, methodDescriptor);
    }

    private void pushBeanDefinitionMethodInvocation(MethodVisitor buildMethodVisitor, String methodName) {
        buildMethodVisitor.visitVarInsn(ALOAD, 0);
        buildMethodVisitor.visitVarInsn(ALOAD, 1);
        buildMethodVisitor.visitVarInsn(ALOAD, 2);
        buildMethodVisitor.visitVarInsn(ALOAD, buildInstanceIndex);

        buildMethodVisitor.visitMethodInsn(INVOKEVIRTUAL,
                beanDefinitionInternalName,
                methodName,
                METHOD_DESCRIPTOR_INITIALIZE,
                false);
    }

    private int pushNewBuildLocalVariable() {
        buildMethodVisitor.visitVarInsn(ASTORE, buildMethodLocalCount);
        return buildMethodLocalCount++;
    }

    private int pushNewInjectLocalVariable() {
        injectMethodVisitor.visitVarInsn(ASTORE, injectMethodLocalCount);
        return injectMethodLocalCount++;
    }

    private int pushNewPostConstructLocalVariable() {
        postConstructMethodVisitor.visitVarInsn(ASTORE, postConstructMethodLocalCount);
        return postConstructMethodLocalCount++;
    }

    private int pushNewPreDestroyLocalVariable() {
        preDestroyMethodVisitor.visitVarInsn(ASTORE, preDestroyMethodLocalCount);
        return preDestroyMethodLocalCount++;
    }

    private void visitBeanDefinitionConstructorInternal(
            MethodElement methodElement,
            boolean requiresReflection) {
        if (constructorVisitor == null) {
            AnnotationMetadata constructorMetadata = methodElement.getAnnotationMetadata();
            DefaultAnnotationMetadata.contributeDefaults(this.annotationMetadata, constructorMetadata);
            ParameterElement[] parameters = methodElement.getParameters();
            List<ParameterElement> parameterList = Arrays.asList(parameters);
            Optional<AnnotationMetadata> argumentQualifier = parameterList
                    .stream()
                    .map(AnnotationMetadataProvider::getAnnotationMetadata)
                    .filter(this::isAnnotatedWithParameter).findFirst();
            boolean isParametrized = argumentQualifier.isPresent();
            if (isParametrized) {
                superType = TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION;
            }


            // create the BeanDefinition constructor for subclasses of AbstractBeanDefinition
            org.objectweb.asm.commons.Method constructorMethod = org.objectweb.asm.commons.Method.getMethod(CONSTRUCTOR_ABSTRACT_BEAN_DEFINITION);
            GeneratorAdapter protectedConstructor = new GeneratorAdapter(
                    classWriter.visitMethod(ACC_PROTECTED, CONSTRUCTOR_NAME, constructorMethod.getDescriptor(), null, null),
                    ACC_PROTECTED,
                    CONSTRUCTOR_NAME,
                    constructorMethod.getDescriptor()
            );
            constructorVisitor = protectedConstructor;

            Type[] beanDefinitionConstructorArgumentTypes = constructorMethod.getArgumentTypes();
            protectedConstructor.loadThis();
            for (int i = 0; i < beanDefinitionConstructorArgumentTypes.length; i++) {
                protectedConstructor.loadArg(i);
            }
            protectedConstructor.invokeConstructor(isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION : superType, BEAN_DEFINITION_CLASS_CONSTRUCTOR);

            GeneratorAdapter defaultConstructor = startConstructor(classWriter);
            GeneratorAdapter defaultConstructorVisitor = new GeneratorAdapter(
                    defaultConstructor,
                    ACC_PUBLIC,
                    CONSTRUCTOR_NAME,
                    DESCRIPTOR_DEFAULT_CONSTRUCTOR
            );
            // ALOAD 0
            defaultConstructor.visitVarInsn(ALOAD, 0);

            // 1st argument: pass the bean definition type as the third argument to super(..)
            defaultConstructor.visitLdcInsn(this.beanType);

            // 2nd Argument: the annotation metadata
            if (constructorMetadata == AnnotationMetadata.EMPTY_METADATA) {
                defaultConstructor.visitInsn(ACONST_NULL);
            } else {
                if (constructorMetadata instanceof AnnotationMetadataHierarchy) {
                    AnnotationMetadataWriter.instantiateNewMetadataHierarchy(
                            beanDefinitionType,
                            classWriter,
                            defaultConstructor,
                            (AnnotationMetadataHierarchy) constructorMetadata,
                            loadTypeMethods);
                } else {

                    AnnotationMetadataWriter.instantiateNewMetadata(
                            beanDefinitionType,
                            classWriter,
                            defaultConstructor,
                            (DefaultAnnotationMetadata) constructorMetadata,
                            loadTypeMethods);
                }
            }

            // 3rd argument: Is reflection required
            defaultConstructor.visitInsn(requiresReflection ? ICONST_1 : ICONST_0);

            // 4th argument: The arguments
            if (parameterList.isEmpty()) {
                defaultConstructor.visitInsn(ACONST_NULL);
            } else {
                pushBuildArgumentsForMethod(
                        beanFullClassName,
                        beanDefinitionType,
                        classWriter,
                        defaultConstructorVisitor,
                        parameterList,
                        loadTypeMethods
                );
                for (ParameterElement value : parameterList) {
                    DefaultAnnotationMetadata.contributeDefaults(this.annotationMetadata, value.getAnnotationMetadata());
                }
            }

            defaultConstructorVisitor.invokeConstructor(
                    beanDefinitionType,
                    BEAN_DEFINITION_CLASS_CONSTRUCTOR
            );

            defaultConstructorVisitor.visitInsn(RETURN);
            defaultConstructorVisitor.visitMaxs(DEFAULT_MAX_STACK, 1);
            defaultConstructorVisitor.visitEnd();
        }
    }

    private GeneratorAdapter buildProtectedConstructor(org.objectweb.asm.commons.Method constructorType) {
        GeneratorAdapter protectedConstructor = new GeneratorAdapter(
                classWriter.visitMethod(ACC_PROTECTED, CONSTRUCTOR_NAME, constructorType.getDescriptor(), null, null),
                ACC_PROTECTED,
                CONSTRUCTOR_NAME,
                constructorType.getDescriptor()
        );

        Type[] arguments = constructorType.getArgumentTypes();
        protectedConstructor.loadThis();
        for (int i = 0; i < arguments.length; i++) {
            protectedConstructor.loadArg(i);
        }
        if (isSuperFactory) {
            protectedConstructor.invokeConstructor(TYPE_ABSTRACT_BEAN_DEFINITION, constructorType);
        } else {
            protectedConstructor.invokeConstructor(superType, constructorType);
        }
        return protectedConstructor;
    }

    private String generateBeanDefSig(String typeParameter) {
        SignatureVisitor sv = new SignatureWriter();
        visitSuperTypeParameters(sv, typeParameter);

        String beanTypeInternalName = getInternalName(typeParameter);
        // visit BeanFactory interface
        for (Class<?> interfaceType : interfaceTypes) {

            SignatureVisitor bfi = sv.visitInterface();
            bfi.visitClassType(Type.getInternalName(interfaceType));
            SignatureVisitor iisv = bfi.visitTypeArgument('=');
            iisv.visitClassType(beanTypeInternalName);
            iisv.visitEnd();
            bfi.visitEnd();
        }
        return sv.toString();
    }

    private void visitSuperTypeParameters(SignatureVisitor sv, String... typeParameters) {
        // visit super class
        SignatureVisitor psv = sv.visitSuperclass();
        psv.visitClassType(isSuperFactory ? TYPE_ABSTRACT_BEAN_DEFINITION.getInternalName() : superType.getInternalName());
        if (superType == TYPE_ABSTRACT_BEAN_DEFINITION || superType == TYPE_ABSTRACT_PARAMETRIZED_BEAN_DEFINITION || isSuperFactory) {
            for (String typeParameter : typeParameters) {

                SignatureVisitor ppsv = psv.visitTypeArgument('=');
                String beanTypeInternalName = getInternalName(typeParameter);
                ppsv.visitClassType(beanTypeInternalName);
                ppsv.visitEnd();
            }
        }

        psv.visitEnd();
    }

    private static Method getBeanLookupMethod(String methodName) {
        return ReflectionUtils.getRequiredInternalMethod(
                AbstractBeanDefinition.class,
                methodName,
                BeanResolutionContext.class,
                BeanContext.class,
                int.class
        );
    }

    private static Method getBeanLookupMethodForArgument(String methodName) {
        return ReflectionUtils.getRequiredInternalMethod(
                AbstractBeanDefinition.class,
                methodName,
                BeanResolutionContext.class,
                BeanContext.class,
                int.class,
                int.class
        );
    }

    /**
     * Data used when visiting method.
     */
    @Internal
    public static final class MethodVisitData {
        private final TypedElement beanType;
        private final boolean requiresReflection;
        private final MethodElement methodElement;

        /**
         * Default constructor.
         *
         * @param beanType           The declaring type
         * @param methodElement      The method element
         * @param requiresReflection Whether reflection is required
         */
        MethodVisitData(
                TypedElement beanType,
                MethodElement methodElement,
                boolean requiresReflection) {
            this.beanType = beanType;
            this.requiresReflection = requiresReflection;
            this.methodElement = methodElement;
        }

        /**
         * @return The method element
         */
        public MethodElement getMethodElement() {
            return methodElement;
        }

        /**
         * @return The declaring type object.
         */
        public TypedElement getBeanType() {
            return beanType;
        }

        /**
         * @return is reflection required
         */
        public boolean isRequiresReflection() {
            return requiresReflection;
        }
    }

    private class FactoryMethodDef {
        private final Type factoryType;
        private final Element factoryMethod;
        private final String methodDescriptor;
        private final int factoryVar;

        public FactoryMethodDef(Type factoryType, Element factoryMethod, String methodDescriptor, int factoryVar) {
            this.factoryType = factoryType;
            this.factoryMethod = factoryMethod;
            this.methodDescriptor = methodDescriptor;
            this.factoryVar = factoryVar;
        }
    }

    private class InnerClassDef {
        private final ClassWriter innerClassWriter;
        private final String constructorInternalName;
        private final Type innerClassType;
        private final String innerClassName;

        public InnerClassDef(String interceptedConstructorWriterName, ClassWriter innerClassWriter, String constructorInternalName, Type innerClassType) {
            this.innerClassName = interceptedConstructorWriterName;
            this.innerClassWriter = innerClassWriter;
            this.constructorInternalName = constructorInternalName;
            this.innerClassType = innerClassType;
        }
    }
}
