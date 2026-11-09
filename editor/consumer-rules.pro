# Rules for the app that uses this module, applied when it is minified with R8.
#
# Nothing is needed: the module uses no reflection and no native code. Its
# @Serializable classes (EditSession, TypefaceDescriptor, LineBreaks) are covered by
# the rules kotlinx.serialization ships with its library.
