package butterknife.compiler;

import com.squareup.javapoet.ClassName;
import com.squareup.javapoet.ParameterizedTypeName;
import com.squareup.javapoet.TypeName;

/**
 * 字段绑定类
 */
final class FieldViewBinding implements MemberViewBinding {
  /**
   * 字段名称
   */
  private final String name;
  /**
   * 字段类型
   */
  private final TypeName type;
  /**
   * 是否必填,是否被Nullable注解修饰
   */
  private final boolean required;

  FieldViewBinding(String name, TypeName type, boolean required) {
    this.name = name;
    this.type = type;
    this.required = required;
  }

  public String getName() {
    return name;
  }

  public TypeName getType() {
    return type;
  }

  public ClassName getRawType() {
    if (type instanceof ParameterizedTypeName) {
      return ((ParameterizedTypeName) type).rawType;
    }
    return (ClassName) type;
  }

  @Override public String getDescription() {
    return "field '" + name + "'";
  }

  public boolean isRequired() {
    return required;
  }
}
