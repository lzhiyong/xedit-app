# Rules for the app that uses this module, applied when it is minified with R8.
#
# Nothing is needed: the module uses no reflection and no native code.
# Its @Serializable classes (common/model.kt, range.kt, position.kt) are covered by the
# rules kotlinx.serialization ships with its library.
