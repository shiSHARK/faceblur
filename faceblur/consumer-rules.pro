# SPDX-License-Identifier: Apache-2.0
#
# Consumer R8/ProGuard rules of the FaceBlur Android library. They ship in the AAR as proguard.txt and are applied
# to every app that depends on FaceBlur and minifies its release build.
#
# ONNX Runtime keeps its public API in ordinary Java classes (ai.onnxruntime.*) but its native library
# (libonnxruntime4j_jni.so) looks up classes, constructors, methods and fields of that package BY NAME at run time
# (the native library holds the names ai/onnxruntime/NodeInfo, TensorInfo, MapInfo, SequenceInfo, OnnxTensor,
# OnnxSequence, OnnxMap, OnnxSparseTensor, OnnxValue, OnnxModelMetadata and OrtException, with their constructors,
# methods and fields). R8 sees no Java caller for most of them, so it removes or renames them and the
# app aborts at the first session open with NoSuchMethodError raised from JNI. ONNX Runtime 1.31.0's own
# proguard.txt keeps only its telemetry classes, so this rule is required here.
#
# Why the whole package and not a list of members: the JNI code resolves many members through string
# lookups spread over the native library (tensors, sequences, maps, sparse tensors, session options, metadata).
# A narrower list would have to be re-derived from the native binary at every ONNX Runtime update, and a missing
# entry fails only at run time on the path that uses it. The ONNX Runtime Java API is small (about 95 classes in
# classes.jar), so keeping it whole costs little size and cannot silently break.
#
# FaceBlur's own classes need no rule: the library uses no reflection, no JNI of its own and no service loader.
-keep class ai.onnxruntime.** { *; }
