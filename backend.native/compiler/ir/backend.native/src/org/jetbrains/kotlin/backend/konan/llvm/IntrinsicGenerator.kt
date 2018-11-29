package org.jetbrains.kotlin.backend.konan.llvm

import llvm.*
import org.jetbrains.kotlin.backend.konan.descriptors.coolInstrinsicAnnotation
import org.jetbrains.kotlin.backend.konan.reportCompilationError
import org.jetbrains.kotlin.ir.declarations.IrFunction
import org.jetbrains.kotlin.ir.expressions.IrCall
import org.jetbrains.kotlin.name.Name

// TODO: Comment each entry
enum class IntrinsicKind {
    PLUS,
    MINUS,
    TIMES,
    DIV,
    REM,
    INC,
    DEC,
    UNARY_PLUS,
    UNARY_MINUS,
    SHL,
    SHR,
    USHR,
    AND,
    OR,
    XOR,
    INV,
    PRIMITIVE_CAST,
    COMPARE_TO,
    NOT,            // Boolean.not()
    TO_BITS,        // {Double, Float}.bits()
    FROM_BITS       // {Double, Float}.fromBits()
}

internal class IntrinsicGenerator(val codegen: CodeGenerator) {

    private val context = codegen.context

    private val integralTypesOrder = arrayOf(int1Type, int8Type, int16Type, int32Type, int64Type)
    private val realTypesOrder = arrayOf(floatType, doubleType)
    
    private val IrFunction.llvmReturnType: LLVMTypeRef
        get() {
            val llvmFunctionTy = codegen.getLlvmFunctionType(this)
            return LLVMGetReturnType(llvmFunctionTy)!!
        }

    // TODO: it can be better
    private fun getIntrinsicKind(function: IrFunction): IntrinsicKind {
        val annotation = function.descriptor.annotations.findAnnotation(coolInstrinsicAnnotation)!!
        val value = annotation.allValueArguments[Name.identifier("kind")]!!.value as String
        return IntrinsicKind.valueOf(value)
    }

    // TODO: set debug info
    fun evaluateCall(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext, currentCodeContext: CodeContext): LLVMValueRef =
        when (getIntrinsicKind(callee.symbol.owner)) {
            IntrinsicKind.PLUS ->           emitPlus(callee, args, functionGenerationContext)
            IntrinsicKind.MINUS ->          emitMinus(callee, args, functionGenerationContext)
            IntrinsicKind.TIMES ->          emitTimes(callee, args, functionGenerationContext)
            IntrinsicKind.DIV ->            emitDiv(callee, args, functionGenerationContext, currentCodeContext)
            IntrinsicKind.REM ->            emitRem(callee, args, functionGenerationContext)
            IntrinsicKind.INC ->            emitInc(callee, args, functionGenerationContext)
            IntrinsicKind.DEC ->            emitDec(callee, args, functionGenerationContext)
            IntrinsicKind.UNARY_PLUS ->     emitUnaryPlus(callee, args, functionGenerationContext)
            IntrinsicKind.UNARY_MINUS ->    emitUnaryMinus(callee, args, functionGenerationContext)
            IntrinsicKind.SHL ->            emitShl(callee, args, functionGenerationContext)
            IntrinsicKind.SHR ->            emitShr(callee, args, functionGenerationContext)
            IntrinsicKind.USHR ->           emitUshr(callee, args, functionGenerationContext)
            IntrinsicKind.AND ->            emitAnd(callee, args, functionGenerationContext)
            IntrinsicKind.OR ->             emitOr(callee, args, functionGenerationContext)
            IntrinsicKind.XOR ->            emitXor(callee, args, functionGenerationContext)
            IntrinsicKind.INV ->            emitInv(callee, args, functionGenerationContext)
            IntrinsicKind.COMPARE_TO ->     emitCompareTo(callee, args, functionGenerationContext)
            IntrinsicKind.PRIMITIVE_CAST -> emitPrimitiveCast(callee, args, functionGenerationContext)
            IntrinsicKind.NOT ->            emitNot(callee, args, functionGenerationContext)
            IntrinsicKind.FROM_BITS ->      emitReinterpret(callee, args, functionGenerationContext)
            IntrinsicKind.TO_BITS ->        emitReinterpret(callee, args, functionGenerationContext)
        }!!

    private fun emitReinterpret(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            bitcast(callee.symbol.owner.llvmReturnType, args[0])
        }


    private fun emitNot(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            not(args[0])
        }
    
    private fun emitPlus(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFAdd(builder, first, second, "")
            } else {
                LLVMBuildAdd(builder, first, second, "")
            }
        }

    private fun emitPrimitiveCast(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            cast(args[0], callee.symbol.owner.llvmReturnType)
        }

    private fun emitShift(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext)
            : Pair<LLVMValueRef, LLVMValueRef> = with (functionGenerationContext) {
        val first = args[0]
        val second = args[1]
        val shift = if (first.type == int64Type) {
            val tmp = LLVMBuildAnd(builder, second, Int32(63).llvm, "")
            LLVMBuildZExt(builder, tmp, int64Type, "")
        } else {
            LLVMBuildAnd(builder, second, Int32(31).llvm, "")
        }
        Pair(first, shift!!)
    }

    // TODO: unify using `shift`
    private fun emitShl(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val (first, shift) = emitShift(callee, args, functionGenerationContext)
            LLVMBuildShl(builder, first, shift, "")
        }

    private fun emitShr(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val (first, shift) = emitShift(callee, args, functionGenerationContext)
            LLVMBuildAShr(builder, first, shift, "")
        }

    private fun emitUshr(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val (first, shift) = emitShift(callee, args, functionGenerationContext)
            LLVMBuildLShr(builder, first, shift, "")
        }

    private fun emitAnd(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            and(first, second)
        }

    private fun emitOr(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            or(first, second)
        }

    private fun emitXor(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            xor(first, second)
        }

    private fun emitInv(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with(functionGenerationContext) {
            val first = args[0]
            val mask = makeConstOfType(first.type, -1)
            xor(first, mask)
        }

    private fun emitMinus(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFSub(builder, first, second, "")
            } else {
                LLVMBuildSub(builder, first, second, "")
            }    
        }

    private fun emitTimes(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFMul(builder, first, second, "")
            } else {
                LLVMBuildMul(builder, first, second, "")
            }
        }

    private fun emitDiv(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext, currentCodeContext: CodeContext) =
        with (functionGenerationContext) {
            val divider = args[1]
            if (divider.type.isFloatingPoint()) {
                return emitDivNoThrow(callee, args, functionGenerationContext)
            }
            // TODO: debug info
            val validArgBb = basicBlock(locationInfo = null)
            val invalidArgBb = basicBlock(locationInfo = null)
            val isZero = icmpEq(divider, Zero(divider.type).llvm)
            condBr(isZero, invalidArgBb, validArgBb)

            positionAtEnd(invalidArgBb)
            val throwArthExc = codegen.llvmFunction(context.ir.symbols.throwArithmeticException.owner)
            call(throwArthExc, listOf(codegen.kNullObjHeaderPtrPtr), verbatim = true, exceptionHandler = currentCodeContext.exceptionHandler)
            unreachable()

            // No need for exit basic block.
            positionAtEnd(validArgBb)
            emitDivNoThrow(callee, args, functionGenerationContext)
        }

    private fun emitDivNoThrow(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFDiv(builder, first, second, "")
            } else {
                LLVMBuildSDiv(builder, first, second, "")
            }
        }

    private fun emitRem(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val function = callee.symbol.owner
            val (first, second) = castArgs(args, function.llvmReturnType)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFRem(builder, first, second, "")
            } else {
                LLVMBuildSRem(builder, first, second, "")
            }
        }

    private fun emitInc(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val first = args[0]
            val const1 = makeConstOfType(first.type, 1)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFAdd(builder, first, const1, "")
            } else {
                LLVMBuildAdd(builder, first, const1, "")
            }
        }

    private fun emitDec(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val first = args[0]
            val const1 = makeConstOfType(first.type, 1)
            if (first.type.isFloatingPoint()) {
                LLVMBuildFSub(builder, first, const1, "")
            } else {
                LLVMBuildSub(builder, first, const1, "")
            }
        }

    private fun emitUnaryPlus(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            cast(args[0], callee.symbol.owner.llvmReturnType)
        }

    private fun emitUnaryMinus(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val destTy = callee.symbol.owner.llvmReturnType
            val first = cast(args[0], destTy)
            val const0 = makeConstOfType(destTy, 0)
            if (destTy.isFloatingPoint()) {
                LLVMBuildFSub(builder, const0, first, "")
            } else {
                LLVMBuildSub(builder, const0, first, "")
            }
        }

    private fun emitCompareTo(callee: IrCall, args: List<LLVMValueRef>, functionGenerationContext: FunctionGenerationContext) =
        with (functionGenerationContext) {
            val (first, second) = castArgs(args)
            val equal: LLVMValueRef
            val less: LLVMValueRef
            if (first.type.isFloatingPoint()) {
                equal = fcmpEq(first, second)
                less = fcmpLt(first, second)
            } else {
                equal = icmpEq(first, second)
                less = icmpLt(first, second)
            }
            val tmp = LLVMBuildSelect(builder, less, Int32(-1).llvm, Int32(1).llvm, "")
            LLVMBuildSelect(builder, equal, Int32(0).llvm, tmp, "")
        }

    private fun makeConstOfType(type: LLVMTypeRef, value: Int): LLVMValueRef = when (type) {
        int8Type -> Int8(value.toByte()).llvm
        int16Type -> Char16(value.toChar()).llvm
        int32Type -> Int32(value).llvm
        int64Type -> Int64(value.toLong()).llvm
        floatType -> Float32(value.toFloat()).llvm
        doubleType -> Float64(value.toDouble()).llvm
        else -> context.reportCompilationError("Unexpected primitive type: $type")
    }

    private fun compareIntegralTypes(firstTy: LLVMTypeRef, secondTy: LLVMTypeRef) =
            integralTypesOrder.indexOf(firstTy).compareTo(integralTypesOrder.indexOf(secondTy))

    private fun FunctionGenerationContext.cast(value: LLVMValueRef, destTy: LLVMTypeRef): LLVMValueRef {
        if (value.type == destTy) return value

        if (value.type == floatType && destTy == doubleType) {
            return LLVMBuildFPExt(builder, value, destTy, "")!!
        }
        if (value.type == doubleType && destTy == floatType) {
            return LLVMBuildFPTrunc(builder, value, destTy, "")!!
        }
        if (destTy.isFloatingPoint() && !value.type.isFloatingPoint()) {
            return LLVMBuildSIToFP(builder, value, destTy, "")!!
        }
        val compResult = compareIntegralTypes(value.type, destTy)
        return when {
            compResult < 0 -> sext(value, destTy)
            compResult > 0 -> trunc(value, destTy)
            else -> value
        }
    }

    private fun FunctionGenerationContext.castArgs(args: List<LLVMValueRef>, destTy: LLVMTypeRef? = null): Pair<LLVMValueRef, LLVMValueRef> {
        val first = args[0]
        val second = args[1]
        val unifiedType = destTy ?: findUnifiedType(first.type, second.type)
        val firstResult = cast(first, unifiedType)
        val secondResult = cast(second, unifiedType)
        return firstResult to secondResult
    }

    private fun findUnifiedType(firstTy: LLVMTypeRef, secondTy: LLVMTypeRef) =
        when {
            firstTy == doubleType || secondTy == doubleType -> doubleType
            firstTy == floatType || secondTy == floatType -> floatType
            firstTy == int64Type || secondTy == int64Type -> int64Type
            firstTy == int32Type || secondTy == int32Type -> int32Type
            firstTy == int16Type || secondTy == int16Type -> int16Type
            firstTy == int8Type || secondTy == int8Type -> int8Type
            firstTy == int1Type || secondTy == int1Type -> int1Type
            else -> context.reportCompilationError("Unexpected types: $firstTy $secondTy")
        }
}