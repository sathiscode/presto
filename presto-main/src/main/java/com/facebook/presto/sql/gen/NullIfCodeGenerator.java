/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.gen;

import com.facebook.presto.metadata.FunctionHandle;
import com.facebook.presto.metadata.Signature;
import com.facebook.presto.operator.scalar.ScalarFunctionImplementation;
import com.facebook.presto.spi.function.OperatorType;
import com.facebook.presto.spi.type.Type;
import com.facebook.presto.spi.type.TypeSignature;
import com.facebook.presto.sql.relational.RowExpression;
import com.google.common.collect.ImmutableList;
import io.airlift.bytecode.BytecodeBlock;
import io.airlift.bytecode.BytecodeNode;
import io.airlift.bytecode.Scope;
import io.airlift.bytecode.Variable;
import io.airlift.bytecode.control.IfStatement;
import io.airlift.bytecode.instruction.LabelNode;

import java.util.List;
import java.util.Optional;

import static com.facebook.presto.spi.function.OperatorType.CAST;
import static com.facebook.presto.sql.gen.BytecodeGenerator.generateWrite;
import static com.facebook.presto.sql.gen.BytecodeUtils.ifWasNullPopAndGoto;
import static io.airlift.bytecode.expression.BytecodeExpressions.constantTrue;

public class NullIfCodeGenerator
        implements BytecodeGenerator
{
    @Override
    public BytecodeNode generateExpression(Signature signature, BytecodeGeneratorContext generatorContext, Type returnType, List<RowExpression> arguments, Optional<Variable> outputBlockVariable)
    {
        Scope scope = generatorContext.getScope();

        RowExpression first = arguments.get(0);
        RowExpression second = arguments.get(1);

        LabelNode notMatch = new LabelNode("notMatch");

        // push first arg on the stack
        Variable firstValue = scope.createTempVariable(first.getType().getJavaType());
        BytecodeBlock block = new BytecodeBlock()
                .comment("check if first arg is null")
                .append(generatorContext.generate(first, Optional.empty()))
                .append(ifWasNullPopAndGoto(scope, notMatch, void.class))
                .dup(first.getType().getJavaType())
                .putVariable(firstValue);

        Type firstType = first.getType();
        Type secondType = second.getType();

        // if (equal(cast(first as <common type>), cast(second as <common type>))
        Signature equalsSignature = generatorContext.getFunctionManager().resolveOperator(OperatorType.EQUAL, ImmutableList.of(firstType, secondType));
        ScalarFunctionImplementation equalsFunction = generatorContext.getFunctionManager().getScalarFunctionImplementation(equalsSignature);
        BytecodeNode equalsCall = generatorContext.generateCall(
                equalsSignature.getName(),
                equalsFunction,
                ImmutableList.of(
                        cast(generatorContext, firstValue, firstType, equalsSignature.getArgumentTypes().get(0)),
                        cast(generatorContext, generatorContext.generate(second, Optional.empty()), secondType, equalsSignature.getArgumentTypes().get(1))));

        BytecodeBlock conditionBlock = new BytecodeBlock()
                .append(equalsCall)
                .append(BytecodeUtils.ifWasNullClearPopAndGoto(scope, notMatch, void.class, boolean.class));

        // if first and second are equal, return null
        BytecodeBlock trueBlock = new BytecodeBlock()
                .append(generatorContext.wasNull().set(constantTrue()))
                .pop(first.getType().getJavaType())
                .pushJavaDefault(first.getType().getJavaType());

        // else return first (which is still on the stack
        block.append(new IfStatement()
                .condition(conditionBlock)
                .ifTrue(trueBlock)
                .ifFalse(notMatch));

        outputBlockVariable.ifPresent(output -> block.append(generateWrite(generatorContext, returnType, output)));
        return block;
    }

    private static BytecodeNode cast(
            BytecodeGeneratorContext generatorContext,
            BytecodeNode argument,
            Type actualType,
            TypeSignature requiredType)
    {
        if (actualType.getTypeSignature().equals(requiredType)) {
            return argument;
        }

        FunctionHandle functionHandle = generatorContext
                .getFunctionManager()
                .lookupCast(CAST, actualType.getTypeSignature(), requiredType);

        // TODO: do we need a full function call? (nullability checks, etc)
        return generatorContext.generateCall(CAST.name(), generatorContext.getFunctionManager().getScalarFunctionImplementation(functionHandle), ImmutableList.of(argument));
    }
}
