package chan.annotation

/**
 * Used to indicate element can be used by extensions and can be extended.
 * This imposes some restrictions to deleting and modifying this element.
 */
@MustBeDocumented
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class Extendable
