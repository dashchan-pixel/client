package chan.annotation

/**
 * Used to indicate element can be used by extensions.
 * This imposes some restrictions to deleting and modifying this element.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION, AnnotationTarget.CONSTRUCTOR,
		AnnotationTarget.FIELD, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.SOURCE)
annotation class Public
