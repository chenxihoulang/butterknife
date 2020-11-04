package butterknife.compiler;

import androidx.annotation.UiThread;

import butterknife.OnTouch;
import butterknife.internal.ListenerClass;
import butterknife.internal.ListenerMethod;

import com.google.common.collect.ImmutableList;
import com.squareup.javapoet.AnnotationSpec;
import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.CodeBlock;
import com.squareup.javapoet.JavaFile;
import com.squareup.javapoet.MethodSpec;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;
import com.squareup.javapoet.TypeSpec;
import com.squareup.javapoet.WildcardTypeName;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.TypeElement;
import javax.lang.model.type.TypeMirror;

import static butterknife.compiler.ButterKnifeProcessor.ACTIVITY_TYPE;
import static butterknife.compiler.ButterKnifeProcessor.DIALOG_TYPE;
import static butterknife.compiler.ButterKnifeProcessor.VIEW_TYPE;
import static butterknife.compiler.ButterKnifeProcessor.isSubtypeOfType;
import static com.google.auto.common.MoreElements.getPackage;
import static java.util.Collections.singletonList;
import static java.util.Objects.requireNonNull;
import static javax.lang.model.element.Modifier.FINAL;
import static javax.lang.model.element.Modifier.PRIVATE;
import static javax.lang.model.element.Modifier.PUBLIC;

/**
 * A set of all the bindings requested by a single type.
 */
final class BindingSet implements BindingInformationProvider {
    static final ClassName UTILS = ClassName.get("butterknife.internal", "Utils");
    private static final ClassName VIEW = ClassName.get("android.view", "View");
    private static final ClassName CONTEXT = ClassName.get("android.content", "Context");
    private static final ClassName RESOURCES = ClassName.get("android.content.res", "Resources");
    private static final ClassName UI_THREAD =
            ClassName.get("androidx.annotation", "UiThread");
    private static final ClassName CALL_SUPER =
            ClassName.get("androidx.annotation", "CallSuper");
    private static final ClassName SUPPRESS_LINT =
            ClassName.get("android.annotation", "SuppressLint");
    private static final ClassName UNBINDER = ClassName.get("butterknife", "Unbinder");
    static final ClassName BITMAP_FACTORY = ClassName.get("android.graphics", "BitmapFactory");
    static final ClassName CONTEXT_COMPAT =
            ClassName.get("androidx.core.content", "ContextCompat");
    static final ClassName ANIMATION_UTILS =
            ClassName.get("android.view.animation", "AnimationUtils");

    private final TypeName targetTypeName;
    private final ClassName bindingClassName;
    private final TypeElement enclosingElement;
    private final boolean isFinal;
    private final boolean isView;
    private final boolean isActivity;
    private final boolean isDialog;
    private final ImmutableList<ViewBinding> viewBindings;
    private final ImmutableList<FieldCollectionViewBinding> collectionBindings;
    private final ImmutableList<ResourceBinding> resourceBindings;
    private final @Nullable
    BindingInformationProvider parentBinding;

    private BindingSet(
            TypeName targetTypeName, ClassName bindingClassName, TypeElement enclosingElement,
            boolean isFinal, boolean isView, boolean isActivity, boolean isDialog,
            ImmutableList<ViewBinding> viewBindings,
            ImmutableList<FieldCollectionViewBinding> collectionBindings,
            ImmutableList<ResourceBinding> resourceBindings,
            @Nullable BindingInformationProvider parentBinding) {
        this.isFinal = isFinal;
        this.targetTypeName = targetTypeName;
        this.bindingClassName = bindingClassName;
        this.enclosingElement = enclosingElement;
        this.isView = isView;
        this.isActivity = isActivity;
        this.isDialog = isDialog;
        this.viewBindings = viewBindings;
        this.collectionBindings = collectionBindings;
        this.resourceBindings = resourceBindings;
        this.parentBinding = parentBinding;
    }

    @Override
    public ClassName getBindingClassName() {
        return bindingClassName;
    }

    /**
     * 生成java文件
     *
     * @param sdk
     * @param debuggable
     * @return
     */
    JavaFile brewJava(int sdk, boolean debuggable) {
        TypeSpec bindingConfiguration = createType(sdk, debuggable);
        return JavaFile.builder(bindingClassName.packageName(), bindingConfiguration)
                .addFileComment("Generated code from Butter Knife. Do not modify!")
                .build();
    }

    /**
     * 使用javapoet库,根据解析出来的绑定信息,创建对应的绑定类
     *
     * @param sdk
     * @param debuggable
     * @return
     */
    private TypeSpec createType(int sdk, boolean debuggable) {
        //最终的生成的绑定类,比如:public class IndexActivity_ViewBinding implements Unbinder
        TypeSpec.Builder result = TypeSpec.classBuilder(bindingClassName.simpleName())
                .addModifiers(PUBLIC)
                .addOriginatingElement(enclosingElement);
        if (isFinal) {
            result.addModifiers(FINAL);
        }

        //如果继承了有绑定的基类,则不为空,
        //比如:public class TestActivity_ViewBinding extends IndexActivity_ViewBinding
        if (parentBinding != null) {
            result.superclass(parentBinding.getBindingClassName());
        } else {
            //继承自Unbinder接口
            result.addSuperinterface(UNBINDER);
        }

        //添加target字段: private TestActivity target;
        if (hasTargetField()) {
            result.addField(targetTypeName, "target", PRIVATE);
        }

        if (isView) {
            result.addMethod(createBindingConstructorForView());
        } else if (isActivity) {
            //我们的BindView注解的基本都是Activity,在这里添加生成类的构造方法
            result.addMethod(createBindingConstructorForActivity());
        } else if (isDialog) {
            result.addMethod(createBindingConstructorForDialog());
        }

        //没有view相关的绑定
        if (!constructorNeedsView()) {
            // Add a delegating constructor with a target type + view signature for reflective use.

            //添加一个标记为过期的方法
            result.addMethod(createBindingViewDelegateConstructor());
        }
        result.addMethod(createBindingConstructor(sdk, debuggable));

        if (hasViewBindings() || parentBinding == null) {
            result.addMethod(createBindingUnbindMethod(result));
        }

        return result.build();
    }

    /**
     * @Deprecated
     * @UiThread public TestActivity1_ViewBinding(TestActivity1 target, View source) {
     * this(target, source.getContext());
     * }
     * @deprecated Use {@link #TestActivity1_ViewBinding(TestActivity1, Context)} for direct creation.
     * Only present for runtime invocation through {@code ButterKnife.bind()}.
     */
    private MethodSpec createBindingViewDelegateConstructor() {
        return MethodSpec.constructorBuilder()
                .addJavadoc("@deprecated Use {@link #$T($T, $T)} for direct creation.\n    "
                                + "Only present for runtime invocation through {@code ButterKnife.bind()}.\n",
                        bindingClassName, targetTypeName, CONTEXT)
                .addAnnotation(Deprecated.class)
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target")
                .addParameter(VIEW, "source")
                .addStatement(("this(target, source.getContext())"))
                .build();
    }

    private MethodSpec createBindingConstructorForView() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target");
        if (constructorNeedsView()) {
            builder.addStatement("this(target, target)");
        } else {
            builder.addStatement("this(target, target.getContext())");
        }
        return builder.build();
    }

    /**
     * 创建生成类的构造方法:
     * <p>
     * 有View相关的绑定
     *
     * @return
     * @UiThread public TestActivity_ViewBinding(TestActivity target) {
     * this(target, target.getWindow().getDecorView());
     * }
     * <p>
     * 没有View相关的绑定
     * @UiThread public TestActivity1_ViewBinding(TestActivity1 target) {
     * this(target, target);
     * }
     */
    private MethodSpec createBindingConstructorForActivity() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target");
        //是否有View相关的绑定
        if (constructorNeedsView()) {
            builder.addStatement("this(target, target.getWindow().getDecorView())");
        } else {
            builder.addStatement("this(target, target)");
        }
        return builder.build();
    }

    private MethodSpec createBindingConstructorForDialog() {
        MethodSpec.Builder builder = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC)
                .addParameter(targetTypeName, "target");
        if (constructorNeedsView()) {
            builder.addStatement("this(target, target.getWindow().getDecorView())");
        } else {
            builder.addStatement("this(target, target.getContext())");
        }
        return builder.build();
    }

    /**
     * 添加一个绑定方法
     * <p>
     * 有View相关的绑定
     *
     * @param sdk
     * @param debuggable
     * @return
     * @UiThread public TestActivity_ViewBinding(TestActivity target, View source) {
     * super(target, source);
     * <p>
     * this.target = target;
     * <p>
     * target.btnOk = Utils.findRequiredViewAsType(source, R.id.btnOk, "field 'btnOk'", Button.class);
     * }
     * <p>
     * <p>
     * 没有View相关的绑定
     * @UiThread public TestActivity1_ViewBinding(TestActivity1 target, Context context) {
     * Resources res = context.getResources();
     * target.action_common_quit = res.getString(R.string.action_common_quit);
     * }
     */
    private MethodSpec createBindingConstructor(int sdk, boolean debuggable) {
        MethodSpec.Builder constructor = MethodSpec.constructorBuilder()
                .addAnnotation(UI_THREAD)
                .addModifiers(PUBLIC);

        //是否有事件相关的绑定,比普通方法target多了个final修饰符,因为事件回调的匿名类中会用到target
        if (hasMethodBindings()) {
            constructor.addParameter(targetTypeName, "target", FINAL);
        } else {
            constructor.addParameter(targetTypeName, "target");
        }

        //是否有view相关的绑定
        if (constructorNeedsView()) {
            constructor.addParameter(VIEW, "source");
        } else {
            constructor.addParameter(CONTEXT, "context");
        }

        if (hasUnqualifiedResourceBindings()) {
            // Aapt can change IDs out from underneath us, just suppress since all will work at runtime.
            constructor.addAnnotation(AnnotationSpec.builder(SuppressWarnings.class)
                    .addMember("value", "$S", "ResourceType")
                    .build());
        }

        if (hasOnTouchMethodBindings()) {
            constructor.addAnnotation(AnnotationSpec.builder(SUPPRESS_LINT)
                    .addMember("value", "$S", "ClickableViewAccessibility")
                    .build());
        }

        //基类中是否有绑定
        if (parentBinding != null) {
            //基类中是否有view相关的绑定
            if (parentBinding.constructorNeedsView()) {
                constructor.addStatement("super(target, source)");
            } else if (constructorNeedsView()) {
                constructor.addStatement("super(target, source.getContext())");
            } else {
                constructor.addStatement("super(target, context)");
            }
            constructor.addCode("\n");
        }

        //this.target = target;
        if (hasTargetField()) {
            constructor.addStatement("this.target = target");
            constructor.addCode("\n");
        }

        //是否有View绑定
        if (hasViewBindings()) {
            if (hasViewLocal()) {
                // Local variable in which all views will be temporarily stored.

                // View view;
                constructor.addStatement("$T view", VIEW);
            }

            /**
             * 添加view查找绑定的代码
             * view = Utils.findRequiredView(source, R.id.btnOk, "field 'btnOk' and method 'login'");
             *     target.btnOk = Utils.castView(view, R.id.btnOk, "field 'btnOk'", Button.class);
             *     view7f090064 = view;
             */
            for (ViewBinding binding : viewBindings) {
                addViewBinding(constructor, binding, debuggable);
            }

            for (FieldCollectionViewBinding binding : collectionBindings) {
                constructor.addStatement("$L", binding.render(debuggable));
            }

            if (!resourceBindings.isEmpty()) {
                constructor.addCode("\n");
            }
        }

        if (!resourceBindings.isEmpty()) {
            if (constructorNeedsView()) {
                constructor.addStatement("$T context = source.getContext()", CONTEXT);
            }
            if (hasResourceBindingsNeedingResource(sdk)) {
                constructor.addStatement("$T res = context.getResources()", RESOURCES);
            }
            for (ResourceBinding binding : resourceBindings) {
                constructor.addStatement("$L", binding.render(sdk));
            }
        }

        return constructor.build();
    }

    private MethodSpec createBindingUnbindMethod(TypeSpec.Builder bindingClass) {
        MethodSpec.Builder result = MethodSpec.methodBuilder("unbind")
                .addAnnotation(Override.class)
                .addModifiers(PUBLIC);
        if (!isFinal && parentBinding == null) {
            result.addAnnotation(CALL_SUPER);
        }

        if (hasTargetField()) {
            if (hasFieldBindings()) {
                result.addStatement("$T target = this.target", targetTypeName);
            }
            result.addStatement("if (target == null) throw new $T($S)", IllegalStateException.class,
                    "Bindings already cleared.");
            result.addStatement("$N = null", hasFieldBindings() ? "this.target" : "target");
            result.addCode("\n");
            for (ViewBinding binding : viewBindings) {
                if (binding.getFieldBinding() != null) {
                    result.addStatement("target.$L = null", binding.getFieldBinding().getName());
                }
            }
            for (FieldCollectionViewBinding binding : collectionBindings) {
                result.addStatement("target.$L = null", binding.name);
            }
        }

        if (hasMethodBindings()) {
            result.addCode("\n");
            for (ViewBinding binding : viewBindings) {
                addFieldAndUnbindStatement(bindingClass, result, binding);
            }
        }

        if (parentBinding != null) {
            result.addCode("\n");
            result.addStatement("super.unbind()");
        }
        return result.build();
    }

    private void addFieldAndUnbindStatement(TypeSpec.Builder result, MethodSpec.Builder unbindMethod,
                                            ViewBinding bindings) {
        // Only add fields to the binding if there are method bindings.
        Map<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> classMethodBindings =
                bindings.getMethodBindings();
        if (classMethodBindings.isEmpty()) {
            return;
        }

        String fieldName =
                bindings.isBoundToRoot()
                        ? "viewSource"
                        : "view" + Integer.toHexString(bindings.getId().value);
        result.addField(VIEW, fieldName, PRIVATE);

        // We only need to emit the null check if there are zero required bindings.
        boolean needsNullChecked = bindings.getRequiredBindings().isEmpty();
        if (needsNullChecked) {
            unbindMethod.beginControlFlow("if ($N != null)", fieldName);
        }

        for (ListenerClass listenerClass : classMethodBindings.keySet()) {
            // We need to keep a reference to the listener
            // in case we need to unbind it via a remove method.
            boolean requiresRemoval = !"".equals(listenerClass.remover());
            String listenerField = "null";
            if (requiresRemoval) {
                TypeName listenerClassName = bestGuess(listenerClass.type());
                listenerField = fieldName + ((ClassName) listenerClassName).simpleName();
                result.addField(listenerClassName, listenerField, PRIVATE);
            }

            String targetType = listenerClass.targetType();
            if (!VIEW_TYPE.equals(targetType)) {
                unbindMethod.addStatement("(($T) $N).$N($N)", bestGuess(targetType),
                        fieldName, removerOrSetter(listenerClass, requiresRemoval), listenerField);
            } else {
                unbindMethod.addStatement("$N.$N($N)", fieldName,
                        removerOrSetter(listenerClass, requiresRemoval), listenerField);
            }

            if (requiresRemoval) {
                unbindMethod.addStatement("$N = null", listenerField);
            }
        }

        unbindMethod.addStatement("$N = null", fieldName);

        if (needsNullChecked) {
            unbindMethod.endControlFlow();
        }
    }

    private String removerOrSetter(ListenerClass listenerClass, boolean requiresRemoval) {
        return requiresRemoval
                ? listenerClass.remover()
                : listenerClass.setter();
    }

    /**
     * 添加view查找绑定的代码
     *
     * @param result
     * @param binding
     * @param debuggable
     */
    private void addViewBinding(MethodSpec.Builder result, ViewBinding binding, boolean debuggable) {
        //我们的BindView这里会为true
        if (binding.isSingleFieldBinding()) {
            // Optimize the common case where there's a single binding directly to a field.
            //获取字段相关信息
            FieldViewBinding fieldBinding = requireNonNull(binding.getFieldBinding());
            CodeBlock.Builder builder = CodeBlock.builder()
                    .add("target.$L = ", fieldBinding.getName());

            //是否需要类型转换,当字段的类型不是View类型时,就要转换为对应的子类型
            boolean requiresCast = requiresCast(fieldBinding.getType());
            if (!debuggable || (!requiresCast && !fieldBinding.isRequired())) {
                if (requiresCast) {
                    builder.add("($T) ", fieldBinding.getType());
                }
                builder.add("source.findViewById($L)", binding.getId().code);
            } else {
                /**
                 *     view = Utils.findRequiredView(source, R.id.btnOk, "field 'btnOk' and method 'login'");
                 */
                builder.add("$T.find", UTILS);
                //判断字段是否必须有值,没有被Nullable修饰
                builder.add(fieldBinding.isRequired() ? "RequiredView" : "OptionalView");
                if (requiresCast) {
                    builder.add("AsType");
                }
                builder.add("(source, $L", binding.getId().code);
                if (fieldBinding.isRequired() || requiresCast) {
                    builder.add(", $S", asHumanDescription(singletonList(fieldBinding)));
                }
                if (requiresCast) {
                    builder.add(", $T.class", fieldBinding.getRawType());
                }
                builder.add(")");
            }
            result.addStatement("$L", builder.build());
            return;
        }

        List<MemberViewBinding> requiredBindings = binding.getRequiredBindings();
        if (!debuggable || requiredBindings.isEmpty()) {
            result.addStatement("view = source.findViewById($L)", binding.getId().code);
        } else if (!binding.isBoundToRoot()) {
            result.addStatement("view = $T.findRequiredView(source, $L, $S)", UTILS,
                    binding.getId().code, asHumanDescription(requiredBindings));
        }

        //添加字段绑定代码
        addFieldBinding(result, binding, debuggable);
        addMethodBindings(result, binding, debuggable);
    }

    /**
     * 添加字段绑定代码
     * <p>
     * target.btnOk = Utils.castView(view, R.id.btnOk, "field 'btnOk'", Button.class);
     *
     * @param result
     * @param binding
     * @param debuggable
     */
    private void addFieldBinding(MethodSpec.Builder result, ViewBinding binding, boolean debuggable) {
        FieldViewBinding fieldBinding = binding.getFieldBinding();
        if (fieldBinding != null) {
            //需要转换view
            if (requiresCast(fieldBinding.getType())) {
                if (debuggable) {
                    result.addStatement("target.$L = $T.castView(view, $L, $S, $T.class)",
                            fieldBinding.getName(), UTILS, binding.getId().code,
                            asHumanDescription(singletonList(fieldBinding)), fieldBinding.getRawType());
                } else {
                    result.addStatement("target.$L = ($T) view", fieldBinding.getName(),
                            fieldBinding.getType());
                }
            } else {
                result.addStatement("target.$L = view", fieldBinding.getName());
            }
        }
    }

    private void addMethodBindings(MethodSpec.Builder result, ViewBinding binding,
                                   boolean debuggable) {
        Map<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> classMethodBindings =
                binding.getMethodBindings();
        if (classMethodBindings.isEmpty()) {
            return;
        }

        // We only need to emit the null check if there are zero required bindings.
        boolean needsNullChecked = binding.getRequiredBindings().isEmpty();
        if (needsNullChecked) {
            result.beginControlFlow("if (view != null)");
        }

        // Add the view reference to the binding.
        String fieldName = "viewSource";
        String bindName = "source";
        if (!binding.isBoundToRoot()) {
            fieldName = "view" + Integer.toHexString(binding.getId().value);
            bindName = "view";
        }
        result.addStatement("$L = $N", fieldName, bindName);

        for (Map.Entry<ListenerClass, Map<ListenerMethod, Set<MethodViewBinding>>> e
                : classMethodBindings.entrySet()) {
            ListenerClass listener = e.getKey();
            Map<ListenerMethod, Set<MethodViewBinding>> methodBindings = e.getValue();

            TypeSpec.Builder callback = TypeSpec.anonymousClassBuilder("")
                    .superclass(ClassName.bestGuess(listener.type()));

            for (ListenerMethod method : getListenerMethods(listener)) {
                MethodSpec.Builder callbackMethod = MethodSpec.methodBuilder(method.name())
                        .addAnnotation(Override.class)
                        .addModifiers(PUBLIC)
                        .returns(bestGuess(method.returnType()));
                String[] parameterTypes = method.parameters();
                for (int i = 0, count = parameterTypes.length; i < count; i++) {
                    callbackMethod.addParameter(bestGuess(parameterTypes[i]), "p" + i);
                }

                boolean hasReturnValue = false;
                CodeBlock.Builder builder = CodeBlock.builder();
                Set<MethodViewBinding> methodViewBindings = methodBindings.get(method);
                if (methodViewBindings != null) {
                    for (MethodViewBinding methodBinding : methodViewBindings) {
                        if (methodBinding.hasReturnValue()) {
                            hasReturnValue = true;
                            builder.add("return "); // TODO what about multiple methods?
                        }
                        builder.add("target.$L(", methodBinding.getName());
                        List<Parameter> parameters = methodBinding.getParameters();
                        String[] listenerParameters = method.parameters();
                        for (int i = 0, count = parameters.size(); i < count; i++) {
                            if (i > 0) {
                                builder.add(", ");
                            }

                            Parameter parameter = parameters.get(i);
                            int listenerPosition = parameter.getListenerPosition();

                            if (parameter.requiresCast(listenerParameters[listenerPosition])) {
                                if (debuggable) {
                                    builder.add("$T.castParam(p$L, $S, $L, $S, $L, $T.class)", UTILS,
                                            listenerPosition, method.name(), listenerPosition, methodBinding.getName(), i,
                                            parameter.getType());
                                } else {
                                    builder.add("($T) p$L", parameter.getType(), listenerPosition);
                                }
                            } else {
                                builder.add("p$L", listenerPosition);
                            }
                        }
                        builder.add(");\n");
                    }
                }

                if (!"void".equals(method.returnType()) && !hasReturnValue) {
                    builder.add("return $L;\n", method.defaultReturn());
                }

                callbackMethod.addCode(builder.build());
                callback.addMethod(callbackMethod.build());
            }

            boolean requiresRemoval = listener.remover().length() != 0;
            String listenerField = null;
            if (requiresRemoval) {
                TypeName listenerClassName = bestGuess(listener.type());
                listenerField = fieldName + ((ClassName) listenerClassName).simpleName();
                result.addStatement("$L = $L", listenerField, callback.build());
            }

            String targetType = listener.targetType();
            if (!VIEW_TYPE.equals(targetType)) {
                result.addStatement("(($T) $N).$L($L)", bestGuess(targetType), bindName,
                        listener.setter(), requiresRemoval ? listenerField : callback.build());
            } else {
                result.addStatement("$N.$L($L)", bindName, listener.setter(),
                        requiresRemoval ? listenerField : callback.build());
            }
        }

        if (needsNullChecked) {
            result.endControlFlow();
        }
    }

    private static List<ListenerMethod> getListenerMethods(ListenerClass listener) {
        if (listener.method().length == 1) {
            return Arrays.asList(listener.method());
        }

        try {
            List<ListenerMethod> methods = new ArrayList<>();
            Class<? extends Enum<?>> callbacks = listener.callbacks();
            for (Enum<?> callbackMethod : callbacks.getEnumConstants()) {
                Field callbackField = callbacks.getField(callbackMethod.name());
                ListenerMethod method = callbackField.getAnnotation(ListenerMethod.class);
                if (method == null) {
                    throw new IllegalStateException(String.format("@%s's %s.%s missing @%s annotation.",
                            callbacks.getEnclosingClass().getSimpleName(), callbacks.getSimpleName(),
                            callbackMethod.name(), ListenerMethod.class.getSimpleName()));
                }
                methods.add(method);
            }
            return methods;
        } catch (NoSuchFieldException e) {
            throw new AssertionError(e);
        }
    }

    static String asHumanDescription(Collection<? extends MemberViewBinding> bindings) {
        Iterator<? extends MemberViewBinding> iterator = bindings.iterator();
        switch (bindings.size()) {
            case 1:
                return iterator.next().getDescription();
            case 2:
                return iterator.next().getDescription() + " and " + iterator.next().getDescription();
            default:
                StringBuilder builder = new StringBuilder();
                for (int i = 0, count = bindings.size(); i < count; i++) {
                    if (i != 0) {
                        builder.append(", ");
                    }
                    if (i == count - 1) {
                        builder.append("and ");
                    }
                    builder.append(iterator.next().getDescription());
                }
                return builder.toString();
        }
    }

    private static TypeName bestGuess(String type) {
        switch (type) {
            case "void":
                return TypeName.VOID;
            case "boolean":
                return TypeName.BOOLEAN;
            case "byte":
                return TypeName.BYTE;
            case "char":
                return TypeName.CHAR;
            case "double":
                return TypeName.DOUBLE;
            case "float":
                return TypeName.FLOAT;
            case "int":
                return TypeName.INT;
            case "long":
                return TypeName.LONG;
            case "short":
                return TypeName.SHORT;
            default:
                int left = type.indexOf('<');
                if (left != -1) {
                    ClassName typeClassName = ClassName.bestGuess(type.substring(0, left));
                    List<TypeName> typeArguments = new ArrayList<>();
                    do {
                        typeArguments.add(WildcardTypeName.subtypeOf(Object.class));
                        left = type.indexOf('<', left + 1);
                    } while (left != -1);
                    return ParameterizedTypeName.get(typeClassName,
                            typeArguments.toArray(new TypeName[typeArguments.size()]));
                }
                return ClassName.bestGuess(type);
        }
    }

    /**
     * True when this type's bindings require a view hierarchy.
     */
    private boolean hasViewBindings() {
        return !viewBindings.isEmpty() || !collectionBindings.isEmpty();
    }

    /**
     * True when this type's bindings use raw integer values instead of {@code R} references.
     */
    private boolean hasUnqualifiedResourceBindings() {
        for (ResourceBinding binding : resourceBindings) {
            if (!binding.id().qualifed) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when this type's bindings use Resource directly instead of Context.
     */
    private boolean hasResourceBindingsNeedingResource(int sdk) {
        for (ResourceBinding binding : resourceBindings) {
            if (binding.requiresResources(sdk)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasMethodBindings() {
        for (ViewBinding bindings : viewBindings) {
            if (!bindings.getMethodBindings().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasOnTouchMethodBindings() {
        for (ViewBinding bindings : viewBindings) {
            if (bindings.getMethodBindings()
                    .containsKey(OnTouch.class.getAnnotation(ListenerClass.class))) {
                return true;
            }
        }
        return false;
    }

    private boolean hasFieldBindings() {
        for (ViewBinding bindings : viewBindings) {
            if (bindings.getFieldBinding() != null) {
                return true;
            }
        }
        return !collectionBindings.isEmpty();
    }

    private boolean hasTargetField() {
        return hasFieldBindings() || hasMethodBindings();
    }

    private boolean hasViewLocal() {
        for (ViewBinding bindings : viewBindings) {
            if (bindings.requiresLocal()) {
                return true;
            }
        }
        return false;
    }

    /**
     * True if this binding requires a view. Otherwise only a context is needed.
     */
    @Override
    public boolean constructorNeedsView() {
        return hasViewBindings() //
                || (parentBinding != null && parentBinding.constructorNeedsView());
    }

    static boolean requiresCast(TypeName type) {
        return !VIEW_TYPE.equals(type.toString());
    }

    @Override
    public String toString() {
        return bindingClassName.toString();
    }

    /**
     * 解析被注解的元素所属的类的相关信息
     *
     * @param enclosingElement 被注解的元素所属的类
     * @return
     */
    static Builder newBuilder(TypeElement enclosingElement) {
        TypeMirror typeMirror = enclosingElement.asType();

        boolean isView = isSubtypeOfType(typeMirror, VIEW_TYPE);
        //我们的BindView注解的元素,一般都在android.app.Activity中
        boolean isActivity = isSubtypeOfType(typeMirror, ACTIVITY_TYPE);
        boolean isDialog = isSubtypeOfType(typeMirror, DIALOG_TYPE);

        //类型名称
        TypeName targetType = TypeName.get(typeMirror);
        if (targetType instanceof ParameterizedTypeName) {
            targetType = ((ParameterizedTypeName) targetType).rawType;
        }

        //获取生成的BindView类文件名称
        ClassName bindingClassName = getBindingClassName(enclosingElement);

        boolean isFinal = enclosingElement.getModifiers().contains(Modifier.FINAL);

        return new Builder(targetType, bindingClassName, enclosingElement, isFinal, isView, isActivity,
                isDialog);
    }

    /**
     * 获取生成的BindView类文件名称
     *
     * @param typeElement
     * @return
     */
    static ClassName getBindingClassName(TypeElement typeElement) {
        //获取所属类的包名
        String packageName = getPackage(typeElement).getQualifiedName().toString();

        //获取类名,如果是内部类,将点号替换为$符号
        String className = typeElement.getQualifiedName().toString().substring(
                packageName.length() + 1).replace('.', '$');

        //生成文件的类名
        return ClassName.get(packageName, className + "_ViewBinding");
    }

    /***
     * 存储类和对应的字段信息
     */
    static final class Builder {
        /**
         * 类型名称
         */
        private final TypeName targetTypeName;
        /**
         * 生成的类名称
         */
        private final ClassName bindingClassName;
        /**
         * 类所对应的类型元素信息
         */
        private final TypeElement enclosingElement;

        private final boolean isFinal;
        private final boolean isView;
        private final boolean isActivity;
        private final boolean isDialog;

        /**
         * 有绑定的父类
         */
        private @Nullable
        BindingInformationProvider parentBinding;
        /**
         * 字段或方法的唯一标识:字段或方法的详情信息
         */
        private final Map<Id, ViewBinding.Builder> viewIdMap = new LinkedHashMap<>();

        private final ImmutableList.Builder<FieldCollectionViewBinding> collectionBindings =
                ImmutableList.builder();
        private final ImmutableList.Builder<ResourceBinding> resourceBindings = ImmutableList.builder();

        private Builder(
                TypeName targetTypeName, ClassName bindingClassName, TypeElement enclosingElement,
                boolean isFinal, boolean isView, boolean isActivity, boolean isDialog) {
            this.targetTypeName = targetTypeName;
            this.bindingClassName = bindingClassName;
            this.enclosingElement = enclosingElement;
            this.isFinal = isFinal;
            this.isView = isView;
            this.isActivity = isActivity;
            this.isDialog = isDialog;
        }

        /**
         * 添加字段
         *
         * @param id
         * @param binding
         */
        void addField(Id id, FieldViewBinding binding) {
            getOrCreateViewBindings(id).setFieldBinding(binding);
        }

        void addFieldCollection(FieldCollectionViewBinding binding) {
            collectionBindings.add(binding);
        }

        boolean addMethod(
                Id id,
                ListenerClass listener,
                ListenerMethod method,
                MethodViewBinding binding) {
            ViewBinding.Builder viewBinding = getOrCreateViewBindings(id);
            if (viewBinding.hasMethodBinding(listener, method) && !"void".equals(method.returnType())) {
                return false;
            }
            viewBinding.addMethodBinding(listener, method, binding);
            return true;
        }

        void addResource(ResourceBinding binding) {
            resourceBindings.add(binding);
        }

        /**
         * 记录下基类信息,说明基类也有绑定
         *
         * @param parent
         */
        void setParent(BindingInformationProvider parent) {
            this.parentBinding = parent;
        }

        @Nullable
        String findExistingBindingName(Id id) {
            ViewBinding.Builder builder = viewIdMap.get(id);
            if (builder == null) {
                return null;
            }
            FieldViewBinding fieldBinding = builder.fieldBinding;
            if (fieldBinding == null) {
                return null;
            }
            return fieldBinding.getName();
        }

        private ViewBinding.Builder getOrCreateViewBindings(Id id) {
            ViewBinding.Builder viewId = viewIdMap.get(id);
            if (viewId == null) {
                viewId = new ViewBinding.Builder(id);
                viewIdMap.put(id, viewId);
            }
            return viewId;
        }

        BindingSet build() {
            ImmutableList.Builder<ViewBinding> viewBindings = ImmutableList.builder();
            for (ViewBinding.Builder builder : viewIdMap.values()) {
                viewBindings.add(builder.build());
            }
            return new BindingSet(targetTypeName, bindingClassName, enclosingElement, isFinal, isView,
                    isActivity, isDialog, viewBindings.build(), collectionBindings.build(),
                    resourceBindings.build(), parentBinding);
        }
    }
}

interface BindingInformationProvider {
    /**
     * 是否有View相关的注解元素
     *
     * @return
     */
    boolean constructorNeedsView();

    /**
     * 生成的类名
     *
     * @return
     */
    ClassName getBindingClassName();
}

/**
 * 某个类中是否有被View相关的注解元素
 */
final class ClasspathBindingSet implements BindingInformationProvider {
    /**
     * 是否有被View相关的注解元素
     */
    private boolean constructorNeedsView;
    /**
     * 生成的类名
     */
    private ClassName className;

    ClasspathBindingSet(boolean constructorNeedsView, TypeElement classElement) {
        this.constructorNeedsView = constructorNeedsView;
        this.className = BindingSet.getBindingClassName(classElement);
    }

    @Override
    public ClassName getBindingClassName() {
        return className;
    }

    @Override
    public boolean constructorNeedsView() {
        return constructorNeedsView;
    }
}
