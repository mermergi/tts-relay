// androidx.annotation.IntDef 的替身：就一个编译期注解，运行时零影响
package androidx.annotation

@Retention(AnnotationRetention.SOURCE)
@Target(AnnotationTarget.ANNOTATION_CLASS, AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY, AnnotationTarget.FUNCTION, AnnotationTarget.LOCAL_VARIABLE)
annotation class IntDef(vararg val value: Int)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY,
        AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FIELD, AnnotationTarget.VALUE_PARAMETER)
annotation class RequiresApi(val api: Int)

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD,
        AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.PROPERTY)
annotation class RequiresPermission(vararg val value: String)

@Retention(AnnotationRetention.BINARY)
annotation class Keep

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.CLASS)
annotation class Nullable

@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.VALUE_PARAMETER, AnnotationTarget.FUNCTION, AnnotationTarget.FIELD,
        AnnotationTarget.PROPERTY, AnnotationTarget.LOCAL_VARIABLE, AnnotationTarget.CLASS)
annotation class NonNull
