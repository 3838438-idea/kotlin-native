/*
 * Copyright 2010-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the LICENSE file.
 */

package org.jetbrains.kotlin.backend.konan.lower

import org.jetbrains.kotlin.backend.common.ClassLoweringPass
import org.jetbrains.kotlin.backend.common.FileLoweringPass
import org.jetbrains.kotlin.backend.common.deepCopyWithVariables
import org.jetbrains.kotlin.backend.common.descriptors.WrappedClassConstructorDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedClassDescriptor
import org.jetbrains.kotlin.backend.common.descriptors.WrappedValueParameterDescriptor
import org.jetbrains.kotlin.backend.common.ir.buildSimpleDelegatingConstructor
import org.jetbrains.kotlin.backend.common.ir.copy
import org.jetbrains.kotlin.backend.common.lower.createIrBuilder
import org.jetbrains.kotlin.backend.common.lower.irBlockBody
import org.jetbrains.kotlin.backend.common.runOnFilePostfix
import org.jetbrains.kotlin.backend.konan.Context
import org.jetbrains.kotlin.backend.konan.DECLARATION_ORIGIN_ENUM
import org.jetbrains.kotlin.backend.konan.descriptors.synthesizedName
import org.jetbrains.kotlin.backend.konan.irasdescriptors.constructedClass
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.IrStatement
import org.jetbrains.kotlin.ir.builders.*
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.*
import org.jetbrains.kotlin.ir.expressions.*
import org.jetbrains.kotlin.ir.expressions.IrStatementOrigin.ARGUMENTS_REORDERING_FOR_CALL
import org.jetbrains.kotlin.ir.expressions.impl.*
import org.jetbrains.kotlin.ir.symbols.IrClassSymbol
import org.jetbrains.kotlin.ir.symbols.IrConstructorSymbol
import org.jetbrains.kotlin.ir.symbols.impl.IrClassSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrConstructorSymbolImpl
import org.jetbrains.kotlin.ir.symbols.impl.IrValueParameterSymbolImpl
import org.jetbrains.kotlin.ir.types.IrType
import org.jetbrains.kotlin.ir.types.classifierOrNull
import org.jetbrains.kotlin.ir.types.typeWith
import org.jetbrains.kotlin.ir.util.*
import org.jetbrains.kotlin.ir.visitors.IrElementTransformerVoid
import org.jetbrains.kotlin.ir.visitors.transformChildrenVoid
import org.jetbrains.kotlin.name.Name

internal class EnumSyntheticFunctionsBuilder(val context: Context) {
    fun buildValuesExpression(startOffset: Int, endOffset: Int,
                              enumClass: IrClass): IrExpression {

        val loweredEnum = context.specialDeclarationsFactory.getLoweredEnum(enumClass)

        return irCall(startOffset, endOffset, genericValuesSymbol.owner, listOf(enumClass.defaultType))
                .apply {
                    val receiver = IrGetObjectValueImpl(startOffset, endOffset,
                            loweredEnum.implObject.defaultType,
                            loweredEnum.implObject.symbol)
                    putValueArgument(0, IrGetFieldImpl(
                            startOffset,
                            endOffset,
                            loweredEnum.valuesField.symbol,
                            loweredEnum.valuesField.type,
                            receiver
                    ))
                }
    }

    fun buildValueOfExpression(startOffset: Int, endOffset: Int,
                               enumClass: IrClass,
                               value: IrExpression): IrExpression {
        val loweredEnum = context.specialDeclarationsFactory.getLoweredEnum(enumClass)

        return irCall(startOffset, endOffset, genericValueOfSymbol.owner, listOf(enumClass.defaultType))
                .apply {
                    putValueArgument(0, value)
                    val receiver = IrGetObjectValueImpl(startOffset, endOffset,
                            loweredEnum.implObject.defaultType, loweredEnum.implObject.symbol)
                    putValueArgument(1, IrGetFieldImpl(
                            startOffset,
                            endOffset,
                            loweredEnum.valuesField.symbol,
                            loweredEnum.valuesField.type,
                            receiver
                    ))
                }
    }

    private val genericValueOfSymbol = context.ir.symbols.valueOfForEnum

    private val genericValuesSymbol = context.ir.symbols.valuesForEnum
}

internal class EnumUsageLowering(val context: Context)
    : IrElementTransformerVoid(), FileLoweringPass {

    private val enumSyntheticFunctionsBuilder = EnumSyntheticFunctionsBuilder(context)

    override fun lower(irFile: IrFile) {
        visitFile(irFile)
    }

    override fun visitGetEnumValue(expression: IrGetEnumValue): IrExpression {
        val entry = expression.symbol.owner
        return loadEnumEntry(
                expression.startOffset,
                expression.endOffset,
                entry.parentAsClass,
                entry.name
        )
    }

    override fun visitCall(expression: IrCall): IrExpression {
        expression.transformChildrenVoid(this)

        if (expression.symbol != enumValuesSymbol && expression.symbol != enumValueOfSymbol)
            return expression

        val irClassSymbol = expression.getTypeArgument(0)!!.classifierOrNull as? IrClassSymbol
                ?: return expression // Type parameter.

        if (irClassSymbol == context.ir.symbols.enum) return expression // Type parameter erased to 'Enum'.

        val irClass = irClassSymbol.owner

        assert (irClass.kind == ClassKind.ENUM_CLASS)

        return if (expression.symbol == enumValuesSymbol) {
            enumSyntheticFunctionsBuilder.buildValuesExpression(expression.startOffset, expression.endOffset, irClass)
        } else {
            val value = expression.getValueArgument(0)!!
            enumSyntheticFunctionsBuilder.buildValueOfExpression(expression.startOffset, expression.endOffset, irClass, value)
        }
    }

    private val enumValueOfSymbol = context.ir.symbols.enumValueOf

    private val enumValuesSymbol = context.ir.symbols.enumValues

    private fun loadEnumEntry(startOffset: Int, endOffset: Int, enumClass: IrClass, name: Name): IrExpression {
        val loweredEnum = context.specialDeclarationsFactory.getLoweredEnum(enumClass)
        val ordinal = loweredEnum.entriesMap[name]!!
        return irCall(startOffset, endOffset, loweredEnum.itemGetterSymbol.owner, emptyList()).apply {
            dispatchReceiver = IrCallImpl(startOffset, endOffset, loweredEnum.valuesGetter.returnType, loweredEnum.valuesGetter.symbol)
            putValueArgument(0, IrConstImpl.int(startOffset, endOffset, context.irBuiltIns.intType, ordinal))
        }
    }
}

internal class EnumClassLowering(val context: Context) : ClassLoweringPass {

    fun run(irFile: IrFile) {
        runOnFilePostfix(irFile)
        // EnumWhenLowering should be performed before EnumUsageLowering because
        // the latter performs lowering of IrGetEnumValue
        EnumWhenLowering(context).lower(irFile)
        EnumUsageLowering(context).lower(irFile)
    }

    override fun lower(irClass: IrClass) {
        if (irClass.kind != ClassKind.ENUM_CLASS) return
        EnumClassTransformer(irClass).run()
    }

    private interface EnumConstructorCallTransformer {
        fun transform(enumConstructorCall: IrEnumConstructorCall): IrExpression
        fun transform(delegatingConstructorCall: IrDelegatingConstructorCall): IrExpression
    }

    private inner class EnumClassTransformer(val irClass: IrClass) {
        private val loweredEnum = context.specialDeclarationsFactory.getLoweredEnum(irClass)
        private val loweredEnumConstructors = mutableMapOf<IrConstructor, IrConstructor>()
        //private val descriptorToIrConstructorWithDefaultArguments = mutableMapOf<IrConstructor, IrConstructor>()
        private val defaultEnumEntryConstructors = mutableMapOf<IrConstructor, IrConstructor>()
        private val loweredEnumConstructorParameters = mutableMapOf<ValueParameterDescriptor, ValueParameterDescriptor>()
        private val enumSyntheticFunctionsBuilder = EnumSyntheticFunctionsBuilder(context)

        fun run() {
            insertInstanceInitializerCall()
            lowerEnumConstructors(irClass)
            lowerEnumEntriesClasses()
            val defaultClass = createDefaultClassForEnumEntries()
            //println(ir2stringWhole(irClass))
            lowerEnumClassBody(defaultClass)
            createImplObject()
        }

        private fun insertInstanceInitializerCall() {
            irClass.transformChildrenVoid(object: IrElementTransformerVoid() {
                override fun visitClass(declaration: IrClass): IrStatement {
                    // Skip nested
                    return declaration
                }

                override fun visitConstructor(declaration: IrConstructor): IrStatement {
                    declaration.transformChildrenVoid(this)

                    val blockBody = declaration.body as? IrBlockBody
                            ?: throw AssertionError("Unexpected constructor body: ${declaration.body}")
                    if (blockBody.statements.all { it !is IrInstanceInitializerCall }) {
                        blockBody.statements.transformFlat {
                            if (it is IrEnumConstructorCall)
                                listOf(it, IrInstanceInitializerCallImpl(declaration.startOffset, declaration.startOffset,
                                        irClass.symbol, context.irBuiltIns.unitType))
                            else null
                        }
                    }
                    return declaration
                }
            })
        }

        private fun lowerEnumEntriesClasses() {
            irClass.declarations.transformFlat { declaration ->
                if (declaration is IrEnumEntry) {
                    listOfNotNull(declaration, lowerEnumEntryClass(declaration.correspondingClass))
                } else null
            }
        }

        private fun lowerEnumEntryClass(enumEntryClass: IrClass?): IrClass? {
            if (enumEntryClass == null) return null

            lowerEnumConstructors(enumEntryClass)

            return enumEntryClass
        }

        private fun createDefaultClassForEnumEntries(): IrClass? {
            if (!irClass.declarations.any { it is IrEnumEntry && it.correspondingClass == null }) return null
            val descriptor = WrappedClassDescriptor()
            val defaultClass = IrClassImpl(
                    irClass.startOffset, irClass.endOffset,
                    DECLARATION_ORIGIN_ENUM,
                    IrClassSymbolImpl(descriptor),
                    "DEFAULT".synthesizedName,
                    ClassKind.CLASS,
                    Visibilities.PRIVATE,
                    Modality.FINAL,
                    false,
                    false,
                    false,
                    false,
                    false
            )
            descriptor.bind(defaultClass)
            defaultClass.parent = irClass
            irClass.declarations += defaultClass

            defaultClass.createParameterDeclarations()

            for (superConstructor in irClass.constructors) {
                val constructor = defaultClass.buildSimpleDelegatingConstructor(superConstructor, context.irBuiltIns)
                defaultEnumEntryConstructors[superConstructor] = constructor

                for (parameter in constructor.valueParameters) {
                    val defaultValue = superConstructor.valueParameters[parameter.index].defaultValue ?: continue
                    val body = defaultValue.deepCopyWithVariables()
                    body.transformChildrenVoid(ParameterMapper(superConstructor, constructor))
                    body.patchDeclarationParents(constructor)
                    //body.accept(SetDeclarationsParentVisitor, constructor)
                    parameter.defaultValue = body
                }
            }
//            val descriptor = irClass.descriptor
//            val defaultClassDescriptor = ClassDescriptorImpl(descriptor, "DEFAULT".synthesizedName, Modality.FINAL,
//                    ClassKind.CLASS, listOf(descriptor.defaultType), SourceElement.NO_SOURCE, false, LockBasedStorageManager.NO_LOCKS)
//            val defaultClass = IrClassImpl(startOffset, endOffset, IrDeclarationOrigin.DEFINED, defaultClassDescriptor)
//            defaultClass.createParameterDeclarations()
//
//
//            val constructors = mutableSetOf<ClassConstructorDescriptor>()
//
//            descriptor.constructors.forEach {
//                val loweredEnumIrConstructor = loweredEnumConstructors[it]!!
//                val loweredEnumConstructor = loweredEnumIrConstructor.descriptor
//
//                val constructor = defaultClass.addSimpleDelegatingConstructor(
//                        loweredEnumIrConstructor,
//                        context.irBuiltIns,
//                        DECLARATION_ORIGIN_ENUM
//                )
//
//                val constructorDescriptor = constructor.descriptor
//                constructors.add(constructorDescriptor)
//                defaultEnumEntryConstructors.put(loweredEnumConstructor, constructor)
//
//                val irConstructor = descriptorToIrConstructorWithDefaultArguments[loweredEnumConstructor]
//                if (irConstructor != null) {
//                    it.valueParameters.filter { it.declaresDefaultValue() }.forEach { argument ->
//                        val loweredArgument = loweredEnumConstructor.valueParameters[argument.loweredIndex()]
//                        val body = irConstructor.getDefault(loweredArgument)!!.deepCopyWithVariables()
//                        body.transformChildrenVoid(ParameterMapper(constructor))
//                        body.accept(SetDeclarationsParentVisitor, constructor)
//                        constructor.putDefault(constructorDescriptor.valueParameters[loweredArgument.index], body)
//                    }
//                }
//            }
//
//            val memberScope = stub<MemberScope>("enum default class")
//            defaultClassDescriptor.initialize(memberScope, constructors, null)

            defaultClass.superTypes += irClass.defaultType
            defaultClass.addFakeOverrides()

            return defaultClass
        }

        private fun createImplObject() {
            val implObject = loweredEnum.implObject

            val enumEntries = mutableListOf<IrEnumEntry>()
            var i = 0
            while (i < irClass.declarations.size) {
                val declaration = irClass.declarations[i]
                var delete = false
                when (declaration) {
                    is IrEnumEntry -> {
                        enumEntries.add(declaration)
                        delete = true
                    }
                    is IrFunction -> {
                        val body = declaration.body
                        if (body is IrSyntheticBody) {
                            when (body.kind) {
                                IrSyntheticBodyKind.ENUM_VALUEOF ->
                                    declaration.body = createSyntheticValueOfMethodBody(declaration)
                                IrSyntheticBodyKind.ENUM_VALUES ->
                                    declaration.body = createSyntheticValuesMethodBody(declaration)
                            }
                        }
                    }
                }
                if (delete)
                    irClass.declarations.removeAt(i)
                else
                    ++i
            }

            implObject.addChild(createSyntheticValuesPropertyDeclaration(enumEntries))
            implObject.addChild(createValuesPropertyInitializer(enumEntries))

            irClass.addChild(implObject)
        }

        private val genericCreateUninitializedInstanceSymbol = context.ir.symbols.createUninitializedInstance

        private fun createSyntheticValuesPropertyDeclaration(enumEntries: List<IrEnumEntry>): IrPropertyImpl {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset

            val irValuesInitializer = context.createArrayOfExpression(irClass.defaultType,
                    enumEntries
                            .sortedBy { it.descriptor.name }
                            .map {
                                val initializer = it.initializerExpression
                                val entryConstructorCall = when {
                                    initializer is IrCall -> initializer
                                    initializer is IrBlock && initializer.origin == ARGUMENTS_REORDERING_FOR_CALL ->
                                        initializer.statements.last() as IrCall
                                    else -> error("Unexpected initializer: $initializer")
                                }
                                val entryConstructor = entryConstructorCall.symbol.owner as IrConstructor
                                val entryClass = entryConstructor.constructedClass

                                irCall(startOffset, endOffset,
                                        genericCreateUninitializedInstanceSymbol.owner,
                                        listOf(entryClass.defaultType)
                                )

                            }, startOffset, endOffset)
            val irField = loweredEnum.valuesField.apply {
                initializer = IrExpressionBodyImpl(startOffset, endOffset, irValuesInitializer)
            }

            val getter = loweredEnum.valuesGetter

            val receiver = IrGetObjectValueImpl(startOffset, endOffset,
                    loweredEnum.implObject.defaultType, loweredEnum.implObject.symbol)
            val value = IrGetFieldImpl(
                    startOffset,
                    endOffset,
                    loweredEnum.valuesField.symbol,
                    loweredEnum.valuesField.type,
                    receiver
            )
            val returnStatement = IrReturnImpl(
                    startOffset,
                    endOffset,
                    context.irBuiltIns.nothingType,
                    loweredEnum.valuesGetter.symbol,
                    value
            )
            getter.body = IrBlockBodyImpl(startOffset, endOffset, listOf(returnStatement))

            return IrPropertyImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM,
                    false, loweredEnum.valuesField.descriptor, irField, getter, null).also {
                it.parent = loweredEnum.implObject
            }
        }

        private val initInstanceSymbol = context.ir.symbols.initInstance

        private val arrayGetSymbol = context.ir.symbols.array.functions.single { it.descriptor.name == Name.identifier("get") }

        private val arrayType = context.ir.symbols.array.typeWith(irClass.defaultType)

        private fun createValuesPropertyInitializer(enumEntries: List<IrEnumEntry>): IrAnonymousInitializerImpl {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset

            fun IrBlockBodyBuilder.initInstanceCall(instance: IrCall, constructor: IrCall): IrCall =
                    irCall(initInstanceSymbol).apply {
                        putValueArgument(0, instance)
                        putValueArgument(1, constructor)
                    }

            return IrAnonymousInitializerImpl(startOffset, endOffset, DECLARATION_ORIGIN_ENUM, loweredEnum.implObject.descriptor).apply {
                body = context.createIrBuilder(symbol, startOffset, endOffset).irBlockBody(irClass) {
                    val instances = irTemporary(irGetField(irGet(loweredEnum.implObject.thisReceiver!!), loweredEnum.valuesField))
                    enumEntries
                            .sortedBy { it.descriptor.name }
                            .withIndex()
                            .forEach {
                                val instance = irCall(arrayGetSymbol).apply {
                                    dispatchReceiver = irGet(instances)
                                    putValueArgument(0, irInt(it.index))
                                }
                                val initializer = it.value.initializerExpression!!
                                when {
                                    initializer is IrCall -> +initInstanceCall(instance, initializer)
                                    initializer is IrBlock && initializer.origin == ARGUMENTS_REORDERING_FOR_CALL -> {
                                        val statements = initializer.statements
                                        val constructorCall = statements.last() as IrCall
                                        statements[statements.lastIndex] = initInstanceCall(instance, constructorCall)
                                        +initializer
                                    }
                                    else -> error("Unexpected initializer: $initializer")
                                }
                            }
                    +irCall(this@EnumClassLowering.context.ir.symbols.freeze, listOf(arrayType)).apply {
                        extensionReceiver = irGet(loweredEnum.implObject.thisReceiver!!)
                    }
                }
            }
        }

        private fun createSyntheticValuesMethodBody(declaration: IrFunction): IrBody {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val valuesExpression = enumSyntheticFunctionsBuilder.buildValuesExpression(startOffset, endOffset, irClass)

            return IrBlockBodyImpl(startOffset, endOffset).apply {
                statements += IrReturnImpl(
                        startOffset,
                        endOffset,
                        context.irBuiltIns.nothingType,
                        declaration.symbol,
                        valuesExpression
                )
            }
        }

        private fun createSyntheticValueOfMethodBody(declaration: IrFunction): IrBody {
            val startOffset = irClass.startOffset
            val endOffset = irClass.endOffset
            val parameter = declaration.valueParameters[0]
            val value = IrGetValueImpl(startOffset, endOffset, parameter.type, parameter.symbol)
            val valueOfExpression = enumSyntheticFunctionsBuilder.buildValueOfExpression(startOffset, endOffset, irClass, value)

            return IrBlockBodyImpl(startOffset, endOffset).apply {
                statements += IrReturnImpl(
                        startOffset,
                        endOffset,
                        context.irBuiltIns.nothingType,
                        declaration.symbol,
                        valueOfExpression
                )
            }
        }

        private fun lowerEnumConstructors(irClass: IrClass) {
            irClass.declarations.forEachIndexed { index, declaration ->
                if (declaration is IrConstructor)
                    irClass.declarations[index] = transformEnumConstructor(declaration)
            }
        }

        private fun transformEnumConstructor(enumConstructor: IrConstructor): IrConstructor {
            val loweredEnumConstructor = lowerEnumConstructor(enumConstructor)

            enumConstructor.descriptor.valueParameters.filter { it.declaresDefaultValue() }.forEach {
                val body = enumConstructor.getDefault(it)!!
                body.transformChildrenVoid(object: IrElementTransformerVoid() {
                    override fun visitGetValue(expression: IrGetValue): IrExpression {
                        val descriptor = expression.descriptor
                        when (descriptor) {
                            is ValueParameterDescriptor -> {
                                val parameter = loweredEnumConstructor.valueParameters[descriptor.loweredIndex()]
                                return IrGetValueImpl(expression.startOffset,
                                        expression.endOffset,
                                        parameter.type,
                                        parameter.symbol)
                            }
                        }
                        return expression
                    }
                })
                loweredEnumConstructor.valueParameters[it.loweredIndex()].defaultValue = body
                //descriptorToIrConstructorWithDefaultArguments[loweredEnumConstructor] = loweredEnumConstructor
            }
            return loweredEnumConstructor
        }

        private fun lowerEnumConstructor(constructor: IrConstructor): IrConstructorImpl {
            val startOffset = constructor.startOffset
            val endOffset = constructor.endOffset
            val loweredConstructor = WrappedClassConstructorDescriptor(
                    constructor.descriptor.annotations,
                    constructor.descriptor.source
            ).let {
                IrConstructorImpl(
                        startOffset, endOffset,
                        constructor.origin,
                        IrConstructorSymbolImpl(it),
                        constructor.name,
                        Visibilities.PROTECTED,
                        false,
                        false,
                        constructor.isPrimary
                ).apply {
                    it.bind(this)
                    parent = constructor.parent
                    returnType = constructor.returnType
                    body = constructor.body!! // Will be transformed later.
                }
            }

            fun createSynthesizedValueParameter(index: Int, name: String, type: IrType) =
                    WrappedValueParameterDescriptor().let {
                        IrValueParameterImpl(
                                startOffset, endOffset,
                                DECLARATION_ORIGIN_ENUM,
                                IrValueParameterSymbolImpl(it),
                                Name.identifier(name),
                                index,
                                type,
                                null,
                                false,
                                false
                        ).apply {
                            it.bind(this)
                        }
                    }

            loweredConstructor.valueParameters += createSynthesizedValueParameter(0, "name", context.irBuiltIns.stringType)
            loweredConstructor.valueParameters += createSynthesizedValueParameter(1, "ordinal", context.irBuiltIns.intType)
            loweredConstructor.valueParameters += constructor.valueParameters.map {
                it.copy(startOffset, endOffset, it.loweredIndex(), constructor.origin).apply {
                    loweredEnumConstructorParameters[it.descriptor as ValueParameterDescriptor] = this.descriptor as ValueParameterDescriptor
                }
            }

            loweredConstructor.valueParameters.forEach { it.parent = loweredConstructor }

//            val loweredConstructorDescriptor = ClassConstructorDescriptorImpl.createSynthesized(
//                    enumConstructor.descriptor.containingDeclaration,
//                    enumConstructor.descriptor.annotations,
//                    enumConstructor.descriptor.isPrimary,
//                    enumConstructor.descriptor.source
//            )
//            val valueParameters =
//                    listOf(
//                            loweredConstructorDescriptor.createValueParameter(
//                                    0,
//                                    "name",
//                                    context.irBuiltIns.stringType,
//                                    enumConstructor.startOffset,
//                                    enumConstructor.endOffset
//                            ),
//                            loweredConstructorDescriptor.createValueParameter(
//                                    1,
//                                    "ordinal",
//                                    context.irBuiltIns.intType,
//                                    enumConstructor.startOffset,
//                                    enumConstructor.endOffset
//                            )
//                    ) +
//                            enumConstructor.valueParameters.map {
//                                val descriptor = it.descriptor as ValueParameterDescriptor
//                                val loweredValueParameterDescriptor = descriptor.copy(
//                                        loweredConstructorDescriptor,
//                                        it.name,
//                                        descriptor.loweredIndex()
//                                )
//                                loweredEnumConstructorParameters[descriptor] = loweredValueParameterDescriptor
//                                it.copy(loweredValueParameterDescriptor)
//                            }
//
//            loweredConstructorDescriptor.initialize(
//                    valueParameters.map { it.descriptor as ValueParameterDescriptor },
//                    Visibilities.PROTECTED
//            )
//            loweredConstructorDescriptor.returnType = enumConstructor.descriptor.returnType
//            val loweredEnumConstructor = IrConstructorImpl(
//                    enumConstructor.startOffset, enumConstructor.endOffset, enumConstructor.origin,
//                    loweredConstructorDescriptor
//            ).apply {
//                returnType = enumConstructor.returnType
//                body = enumConstructor.body!! // will be transformed later
//            }
//            loweredEnumConstructor.valueParameters += valueParameters
//            loweredEnumConstructor.parent = enumConstructor.parent

            loweredEnumConstructors[constructor] = loweredConstructor

            return loweredConstructor
        }

        private fun lowerEnumClassBody(defaultClass: IrClass?) {
            irClass.transformChildrenVoid(EnumClassBodyTransformer(defaultClass))
        }

        private inner class InEnumClassConstructor(val enumClassConstructor: IrConstructor) :
                EnumConstructorCallTransformer {
            override fun transform(enumConstructorCall: IrEnumConstructorCall): IrExpression {
                val startOffset = enumConstructorCall.startOffset
                val endOffset = enumConstructorCall.endOffset
                val origin = enumConstructorCall.origin

                val result = IrDelegatingConstructorCallImpl(startOffset, endOffset,
                        context.irBuiltIns.unitType,
                        enumConstructorCall.symbol, enumConstructorCall.descriptor, enumConstructorCall.typeArgumentsCount)

                assert(result.descriptor.valueParameters.size == 2) {
                    "Enum(String, Int) constructor call expected:\n${result.dump()}"
                }

                val nameParameter = enumClassConstructor.valueParameters.getOrElse(0) {
                    throw AssertionError("No 'name' parameter in enum constructor: $enumClassConstructor")
                }

                val ordinalParameter = enumClassConstructor.valueParameters.getOrElse(1) {
                    throw AssertionError("No 'ordinal' parameter in enum constructor: $enumClassConstructor")
                }

                result.putValueArgument(0,
                        IrGetValueImpl(startOffset, endOffset, nameParameter.type, nameParameter.symbol, origin)
                )
                result.putValueArgument(1,
                        IrGetValueImpl(startOffset, endOffset, ordinalParameter.type, ordinalParameter.symbol, origin)
                )
                return result
            }

            override fun transform(delegatingConstructorCall: IrDelegatingConstructorCall): IrExpression {
                val descriptor = delegatingConstructorCall.descriptor
                val startOffset = delegatingConstructorCall.startOffset
                val endOffset = delegatingConstructorCall.endOffset

                val loweredDelegatedConstructor = loweredEnumConstructors.getOrElse(delegatingConstructorCall.symbol.owner) {
                    throw AssertionError("Constructor called in enum entry initializer should've been lowered: $descriptor")
                }

                val result = IrDelegatingConstructorCallImpl(startOffset, endOffset, context.irBuiltIns.unitType,
                        loweredDelegatedConstructor.symbol, loweredDelegatedConstructor.descriptor, 0)

                val firstParameter = enumClassConstructor.valueParameters[0]
                result.putValueArgument(0,
                        IrGetValueImpl(startOffset, endOffset, firstParameter.type, firstParameter.symbol))
                val secondParameter = enumClassConstructor.valueParameters[1]
                result.putValueArgument(1,
                        IrGetValueImpl(startOffset, endOffset, secondParameter.type, secondParameter.symbol))

                descriptor.valueParameters.forEach { valueParameter ->
                    result.putValueArgument(valueParameter.loweredIndex(), delegatingConstructorCall.getValueArgument(valueParameter))
                }

                return result
            }
        }

        private abstract inner class InEnumEntry(private val enumEntry: ClassDescriptor) : EnumConstructorCallTransformer {
            override fun transform(enumConstructorCall: IrEnumConstructorCall): IrExpression {
                val name = enumEntry.name.asString()
                val ordinal = context.specialDeclarationsFactory.getEnumEntryOrdinal(enumEntry)

                val descriptor = enumConstructorCall.descriptor
                val startOffset = enumConstructorCall.startOffset
                val endOffset = enumConstructorCall.endOffset

                val loweredConstructor = loweredEnumConstructors.getOrElse(enumConstructorCall.symbol.owner) {
                    throw AssertionError("Constructor called in enum entry initializer should've been lowered: $descriptor")
                }

                val result = createConstructorCall(startOffset, endOffset, loweredConstructor.symbol)

                result.putValueArgument(0,
                        IrConstImpl.string(startOffset, endOffset, context.irBuiltIns.stringType, name))
                result.putValueArgument(1,
                        IrConstImpl.int(startOffset, endOffset, context.irBuiltIns.intType, ordinal))

                descriptor.valueParameters.forEach { valueParameter ->
                    val i = valueParameter.index
                    result.putValueArgument(i + 2, enumConstructorCall.getValueArgument(i))
                }

                return result
            }

            override fun transform(delegatingConstructorCall: IrDelegatingConstructorCall): IrExpression {
                throw AssertionError("Unexpected delegating constructor call within enum entry: $enumEntry")
            }

            abstract fun createConstructorCall(startOffset: Int, endOffset: Int, loweredConstructor: IrConstructorSymbol): IrMemberAccessExpression
        }

        private inner class InEnumEntryClassConstructor(enumEntry: ClassDescriptor) : InEnumEntry(enumEntry) {
            override fun createConstructorCall(
                    startOffset: Int,
                    endOffset: Int,
                    loweredConstructor: IrConstructorSymbol
            ) = IrDelegatingConstructorCallImpl(
                    startOffset,
                    endOffset,
                    context.irBuiltIns.unitType,
                    loweredConstructor,
                    loweredConstructor.descriptor,
                    0
            )
        }

        private inner class InEnumEntryInitializer(enumEntry: ClassDescriptor) : InEnumEntry(enumEntry) {
            override fun createConstructorCall(startOffset: Int, endOffset: Int, loweredConstructor: IrConstructorSymbol): IrCall {
                val irConstructorSymbol = defaultEnumEntryConstructors[loweredConstructor.owner]?.symbol
                        ?: loweredConstructor
                return IrCallImpl(startOffset, endOffset, irConstructorSymbol.owner.returnType, irConstructorSymbol)
            }
        }

        private inner class EnumClassBodyTransformer(val defaultClass: IrClass?) : IrElementTransformerVoid() {
            private var enumConstructorCallTransformer: EnumConstructorCallTransformer? = null

            override fun visitClass(declaration: IrClass): IrStatement {
                if (declaration.descriptor.kind == ClassKind.ENUM_CLASS || declaration == defaultClass)
                    return declaration
                return super.visitClass(declaration)
            }

            override fun visitEnumEntry(declaration: IrEnumEntry): IrStatement {
                assert(enumConstructorCallTransformer == null) { "Nested enum entry initialization:\n${declaration.dump()}" }

                enumConstructorCallTransformer = InEnumEntryInitializer(declaration.descriptor)

                var result: IrEnumEntry = IrEnumEntryImpl(declaration.startOffset, declaration.endOffset, declaration.origin,
                        declaration.descriptor, null, declaration.initializerExpression)
                result = super.visitEnumEntry(result) as IrEnumEntry

                enumConstructorCallTransformer = null

                return result
            }

            override fun visitConstructor(declaration: IrConstructor): IrStatement {
                val constructorDescriptor = declaration.descriptor
                val containingClass = constructorDescriptor.containingDeclaration

                // TODO local (non-enum) class in enum class constructor?
                val previous = enumConstructorCallTransformer

                if (containingClass.kind == ClassKind.ENUM_ENTRY) {
                    assert(enumConstructorCallTransformer == null) { "Nested enum entry initialization:\n${declaration.dump()}" }
                    enumConstructorCallTransformer = InEnumEntryClassConstructor(containingClass)
                } else if (containingClass.kind == ClassKind.ENUM_CLASS) {
                    assert(enumConstructorCallTransformer == null) { "Nested enum entry initialization:\n${declaration.dump()}" }
                    enumConstructorCallTransformer = InEnumClassConstructor(declaration)
                }

                val result = super.visitConstructor(declaration)

                enumConstructorCallTransformer = previous

                return result
            }

            override fun visitEnumConstructorCall(expression: IrEnumConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                val callTransformer = enumConstructorCallTransformer ?:
                throw AssertionError("Enum constructor call outside of enum entry initialization or enum class constructor:\n" + irClass.dump())


                return callTransformer.transform(expression)
            }

            override fun visitDelegatingConstructorCall(expression: IrDelegatingConstructorCall): IrExpression {
                expression.transformChildrenVoid(this)

                if (expression.descriptor.containingDeclaration.kind == ClassKind.ENUM_CLASS) {
                    val callTransformer = enumConstructorCallTransformer ?:
                    throw AssertionError("Enum constructor call outside of enum entry initialization or enum class constructor:\n" + irClass.dump())

                    return callTransformer.transform(expression)
                }
                return expression
            }

            override fun visitGetValue(expression: IrGetValue): IrExpression {
                val loweredParameter = loweredEnumConstructorParameters[expression.descriptor]
                return if (loweredParameter == null) {
                    expression
                } else {
                    //val loweredEnumConstructor = loweredEnumConstructors[expression.descriptor.containingDeclaration]!!
                    val loweredEnumConstructor = loweredEnumConstructors[expression.symbol.owner.parent as IrConstructor]!!
                    val loweredIrParameter = loweredEnumConstructor.valueParameters[loweredParameter.index]
                    assert(loweredIrParameter.descriptor == loweredParameter)
                    IrGetValueImpl(expression.startOffset, expression.endOffset, loweredIrParameter.type,
                            loweredIrParameter.symbol, expression.origin)
                }
            }
        }
    }
}

private fun ValueParameterDescriptor.loweredIndex(): Int = index + 2
private fun IrValueParameter.loweredIndex(): Int = index + 2

private class ParameterMapper(superConstructor: IrConstructor, val constructor: IrConstructor) : IrElementTransformerVoid() {
    private val valueParameters = superConstructor.valueParameters.toSet()

    override fun visitGetValue(expression: IrGetValue): IrExpression {

        val superParameter = expression.symbol.owner
        if (valueParameters.contains(superParameter)) {
            val parameter = constructor.valueParameters[(superParameter as IrValueParameter).index]
            return IrGetValueImpl(
                    expression.startOffset, expression.endOffset,
                    parameter.type,
                    parameter.symbol)
        }
        return expression
    }
}
