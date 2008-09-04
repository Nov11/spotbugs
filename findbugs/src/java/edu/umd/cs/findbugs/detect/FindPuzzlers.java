/*
 * FindBugs - Find bugs in Java programs
 * Copyright (C) 2005, University of Maryland
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package edu.umd.cs.findbugs.detect;


import java.util.ArrayList;
import java.util.Collection;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantString;
import org.apache.bcel.classfile.JavaClass;

import edu.umd.cs.findbugs.BugAccumulator;
import edu.umd.cs.findbugs.BugAnnotation;
import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.IntAnnotation;
import edu.umd.cs.findbugs.LocalVariableAnnotation;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.Priorities;
import edu.umd.cs.findbugs.SourceLineAnnotation;
import edu.umd.cs.findbugs.SystemProperties;
import edu.umd.cs.findbugs.OpcodeStack.Item;
import edu.umd.cs.findbugs.ba.AnalysisContext;
import edu.umd.cs.findbugs.ba.XFactory;
import edu.umd.cs.findbugs.ba.XMethod;
import edu.umd.cs.findbugs.bcel.OpcodeStackDetector;
import edu.umd.cs.findbugs.visitclass.Util;

public class FindPuzzlers extends OpcodeStackDetector {

	private static final boolean VAMISMATCH_DEBUG = SystemProperties.getBoolean("vamismatch.debug");

	final BugReporter bugReporter;
	final BugAccumulator bugAccumulator;
	public FindPuzzlers(BugReporter bugReporter) {
		this.bugReporter =  bugReporter;
		this.bugAccumulator = new BugAccumulator(bugReporter);
	}



	@Override
	public void visit(Code obj) {
		prevOpcodeIncrementedRegister = -1;
		best_priority_for_ICAST_INTEGER_MULTIPLY_CAST_TO_LONG = LOW_PRIORITY+1;
		prevOpCode = NOP;
		previousMethodInvocation = null;
		badlyComputingOddState = 0;
		resetIMulCastLong();
		imul_distance = 10000;
		ternaryConversionState = 0;
		super.visit(obj);
		bugAccumulator.reportAccumulatedBugs();
	}

	int imul_constant;
	int imul_distance;
	boolean imul_operand_is_parameter;
	int prevOpcodeIncrementedRegister;
	int valueOfConstantArgumentToShift;
	int best_priority_for_ICAST_INTEGER_MULTIPLY_CAST_TO_LONG ;
	boolean constantArgumentToShift;
	boolean shiftOfNonnegativeValue;
	int ternaryConversionState = 0;

	int badlyComputingOddState;
	int prevOpCode;
	XMethod previousMethodInvocation;
	boolean isTigerOrHigher;
	
	@Override
	public void visit(JavaClass obj) {
		isTigerOrHigher = obj.getMajor() >= MAJOR_1_5;
	}

	private void resetIMulCastLong() {
		imul_constant = 1;
		imul_operand_is_parameter = false;
	}
	private int adjustPriority(int factor, int priority) {
		if (factor <= 4) return LOW_PRIORITY+2;
		if (factor <= 10000) return priority+1;
		if (factor <= 60*60*1000) return priority;
		return priority-1;
	}
	private int adjustMultiplier(Object constant, int mul) {
		if (!(constant instanceof Integer)) return mul;
		return Math.abs(((Integer) constant).intValue()) * mul;

	}
	@Override
	public void sawOpcode(int seen) {
		if (seen != RETURN && isReturn(seen) && isRegisterStore(getPrevOpcode(1))) {
			
			int priority = Priorities.NORMAL_PRIORITY;
			if  (getMethodSig().endsWith(")Z")) priority =  Priorities.HIGH_PRIORITY;
			else {
				if (getMethodSig().endsWith(")Ljava/lang/String;")) priority =  Priorities.LOW_PRIORITY;
				if (getPC() == getCode().getCode().length-1 ) priority++;
			}
			bugReporter.reportBug(new BugInstance(this, "DLS_DEAD_LOCAL_STORE_IN_RETURN", priority).addClassAndMethod(this).addSourceLine(this));
		}
		// System.out.println(getPC() + " " + OPCODE_NAMES[seen] + " " + ternaryConversionState);
		if (seen == IMUL) {
			if (imul_distance != 1) resetIMulCastLong();
			imul_distance = 0;
			if (stack.getStackDepth() > 1) {
				OpcodeStack.Item item0 = stack.getStackItem(0);
				OpcodeStack.Item item1 = stack.getStackItem(1);
				imul_constant = adjustMultiplier(item0.getConstant(), imul_constant);
				imul_constant = adjustMultiplier(item1.getConstant(), imul_constant);

				if (item0.isInitialParameter() || item1.isInitialParameter())
					imul_operand_is_parameter = true;
			}} else {
				imul_distance++;
			}

		if (prevOpCode == IMUL && seen == I2L) {
			int priority = adjustPriority(imul_constant, NORMAL_PRIORITY);
			if (priority >= LOW_PRIORITY && imul_constant != 1000 && imul_operand_is_parameter) priority = NORMAL_PRIORITY;
			if (priority <= best_priority_for_ICAST_INTEGER_MULTIPLY_CAST_TO_LONG) {
				best_priority_for_ICAST_INTEGER_MULTIPLY_CAST_TO_LONG = priority;
				bugAccumulator.accumulateBug(new BugInstance(this, 
						"ICAST_INTEGER_MULTIPLY_CAST_TO_LONG", 
						priority)
				.addClassAndMethod(this), this);
			}
		}

		if (getMethodName().equals("<clinit>") && (seen == PUTSTATIC || seen == GETSTATIC || seen == INVOKESTATIC)) {
			String clazz = getClassConstantOperand();
			if (!clazz.equals(getClassName())) {
				try {
					JavaClass targetClass = Repository.lookupClass(clazz);
					if (Repository.instanceOf(targetClass, getThisClass())) {
						int priority = NORMAL_PRIORITY;
						if (seen == GETSTATIC) priority--;
						if (!targetClass.isPublic()) priority++;
						bugAccumulator.accumulateBug(new BugInstance(this, 
								"IC_SUPERCLASS_USES_SUBCLASS_DURING_INITIALIZATION", 
								priority)
						.addClassAndMethod(this).addClass(getDottedClassConstantOperand()),
						this);
						
					}
				} catch (ClassNotFoundException e) {
					// ignore it
				}

			}
		}
		if (false && (seen == INVOKEVIRTUAL)
				&&   getNameConstantOperand().equals("equals")
				&&   getSigConstantOperand().equals("(Ljava/lang/Object;)Z")
				&& stack.getStackDepth() > 1) {
			OpcodeStack.Item item0 = stack.getStackItem(0);
			OpcodeStack.Item item1 = stack.getStackItem(1);

			if (item0.isArray() || item1.isArray()) {
				bugAccumulator.accumulateBug(new BugInstance("EC_BAD_ARRAY_COMPARE", NORMAL_PRIORITY)
				.addClassAndMethod(this), this);
			}
		}


		if (seen >= IALOAD && seen <= SALOAD || seen >= IASTORE && seen <= SASTORE ) {
			Item index  = stack.getStackItem(0);
			if (index.getSpecialKind() == Item.AVERAGE_COMPUTED_USING_DIVISION) {
				SourceLineAnnotation where;
				if (index.getPC() >= 0)
					where = SourceLineAnnotation.fromVisitedInstruction(this, index.getPC());
				else where = SourceLineAnnotation.fromVisitedInstruction(this);
				bugAccumulator.accumulateBug(new BugInstance(this, "IM_AVERAGE_COMPUTATION_COULD_OVERFLOW", NORMAL_PRIORITY)
				.addClassAndMethod(this), where);
			}
				
		}

		if ((seen == IFEQ || seen == IFNE) && getPrevOpcode(1) == IMUL
				&& ( getPrevOpcode(2) == SIPUSH
						|| getPrevOpcode(2) == BIPUSH
				)
				&& getPrevOpcode(3) == IREM
		)
			bugAccumulator.accumulateBug(new BugInstance(this, "IM_MULTIPLYING_RESULT_OF_IREM", LOW_PRIORITY)
			.addClassAndMethod(this), this);
			


		if (seen == I2S && getPrevOpcode(1) == IUSHR && !shiftOfNonnegativeValue && 
				(!constantArgumentToShift || valueOfConstantArgumentToShift % 16 != 0)
				|| seen == I2B && getPrevOpcode(1) == IUSHR && !shiftOfNonnegativeValue
				&& (!constantArgumentToShift || valueOfConstantArgumentToShift % 8 != 0)) 

			bugAccumulator.accumulateBug(new BugInstance(this, "ICAST_QUESTIONABLE_UNSIGNED_RIGHT_SHIFT", NORMAL_PRIORITY)
			.addClassAndMethod(this), this);


		constantArgumentToShift = false;
		shiftOfNonnegativeValue = false;
		if ( (seen == IUSHR 
				|| seen == ISHR 
				|| seen == ISHL )) {
			if (stack.getStackDepth() <= 1) {
				// don't understand; lie so other detectors won't get concerned
				constantArgumentToShift = true;
				valueOfConstantArgumentToShift = 8;
			}
			else {
				Object rightHandSide
				= stack.getStackItem(0).getConstant();

				Object leftHandSide 
				=  stack.getStackItem(1).getConstant();
				shiftOfNonnegativeValue = stack.getStackItem(1).isNonNegative();
				if (rightHandSide instanceof Integer) {
					constantArgumentToShift = true;
					valueOfConstantArgumentToShift = ((Integer) rightHandSide);
					if (valueOfConstantArgumentToShift < 0 || valueOfConstantArgumentToShift >= 32)
						bugAccumulator.accumulateBug(new BugInstance(this, "ICAST_BAD_SHIFT_AMOUNT", 
								valueOfConstantArgumentToShift < 0 ? LOW_PRIORITY : HIGH_PRIORITY)
						.addClassAndMethod(this)
						.addInt(valueOfConstantArgumentToShift).describe(IntAnnotation.INT_SHIFT), this);
				}
				if (leftHandSide != null 
						&& leftHandSide instanceof Integer
						&& ((Integer) leftHandSide)
						> 0) {
					// boring; lie so other detectors won't get concerned
					constantArgumentToShift = true;
					valueOfConstantArgumentToShift = 8;
				}
			}
		}



		if (seen == INVOKEVIRTUAL && stack.getStackDepth() > 0
				&& getClassConstantOperand().equals("java/util/Date")
				&& getNameConstantOperand().equals("setMonth")
				&& getSigConstantOperand().equals("(I)V")) {
			OpcodeStack.Item item = stack.getStackItem(0);
			Object o = item.getConstant();
			if (o != null && o instanceof Integer) {
				int v = (Integer) o;
				if (v < 0 || v > 11)
					bugReporter.reportBug(new BugInstance(this, "DMI_BAD_MONTH", HIGH_PRIORITY)
					.addClassAndMethod(this)
					.addInt(v).describe(IntAnnotation.INT_VALUE)
					.addCalledMethod(this)
					.addSourceLine(this)
					);
			}
		}

		if (seen == INVOKEVIRTUAL && stack.getStackDepth() > 1
				&& getClassConstantOperand().equals("java/util/Calendar")
				&& getNameConstantOperand().equals("set")

				||
				seen == INVOKESPECIAL && stack.getStackDepth() > 1
				&& getClassConstantOperand().equals("java/util/GregorianCalendar")
				&& getNameConstantOperand().equals("<init>")

		) {
			String sig = getSigConstantOperand();
			if (sig.startsWith("(III")) {
				int pos = sig.length() - 5;
				OpcodeStack.Item item = stack.getStackItem(pos);
				Object o = item.getConstant();
				if (o != null && o instanceof Integer) {
					int v = (Integer) o;
					if (v < 0 || v > 11)
						bugReporter.reportBug(new BugInstance(this, "DMI_BAD_MONTH", NORMAL_PRIORITY)
						.addClassAndMethod(this)
						.addInt(v).describe(IntAnnotation.INT_VALUE)
						.addCalledMethod(this)
						.addSourceLine(this)
						);
				}
			}
		}



		if (isRegisterStore() && (seen == ISTORE 
				|| seen == ISTORE_0
				|| seen == ISTORE_1
				|| seen == ISTORE_2
				|| seen == ISTORE_3)
				&& getRegisterOperand() == prevOpcodeIncrementedRegister) {
			bugAccumulator.accumulateBug(new BugInstance(this, "DLS_OVERWRITTEN_INCREMENT", HIGH_PRIORITY)
			.addClassAndMethod(this), this);
			

		}
		if (seen == IINC) {
			prevOpcodeIncrementedRegister = getRegisterOperand();	
		}
		else
			prevOpcodeIncrementedRegister = -1;


		// Java Puzzlers, Chapter 2, puzzle 1
		// Look for ICONST_2 IREM ICONST_1  IF_ICMPNE L1

		switch (badlyComputingOddState) {
		case 0:
			if (seen == ICONST_2) badlyComputingOddState++;
			break;
		case 1:
			if (seen == IREM) {
				OpcodeStack.Item item = stack.getStackItem(1);
				if (!item.isNonNegative() && item.getSpecialKind() != OpcodeStack.Item.MATH_ABS)
					badlyComputingOddState++;
				else  badlyComputingOddState = 0;
			}
			else badlyComputingOddState = 0;
			break;
		case 2:
			if (seen == ICONST_1) badlyComputingOddState++;
			else badlyComputingOddState = 0;
			break;
		case 3:
			if (seen == IF_ICMPEQ || seen == IF_ICMPNE)  {
				bugAccumulator.accumulateBug(new BugInstance(this, "IM_BAD_CHECK_FOR_ODD", NORMAL_PRIORITY)
				.addClassAndMethod(this), this);
			}
			badlyComputingOddState = 0;
			break;
		}

		// Java Puzzlers, chapter 3, puzzle 12
		if (seen == INVOKEVIRTUAL && stack.getStackDepth() > 0 
				&& (getNameConstantOperand().equals("toString")
						&& getSigConstantOperand().equals("()Ljava/lang/String;")
						|| getNameConstantOperand().equals("append")
						&& getSigConstantOperand().equals("(Ljava/lang/Object;)Ljava/lang/StringBuilder;") && getClassConstantOperand().equals("java/lang/StringBuilder")
						|| getNameConstantOperand().equals("append")
						&& getSigConstantOperand().equals("(Ljava/lang/Object;)Ljava/lang/StringBuffer;") && getClassConstantOperand().equals("java/lang/StringBuffer")
				)
		) {
			OpcodeStack.Item item = stack.getStackItem(0);
			String signature = item.getSignature();
			if (signature != null && signature.startsWith("[")) {
				String name = null;
				int reg = item.getRegisterNumber();
				Collection<BugAnnotation> as = new ArrayList<BugAnnotation>();
				if(reg != -1) {
					LocalVariableAnnotation lva =
						LocalVariableAnnotation.getLocalVariableAnnotation(
							getMethod(), reg, getPC(), getPC()-1);
					name = "array " + lva.getName() + "[]";
					as.add(lva);
				}
				if(name != null) {
					bugAccumulator.accumulateBug(
							new BugInstance(this, "DMI_INVOKING_TOSTRING_ON_ARRAY", NORMAL_PRIORITY)
					.addClassAndMethod(this)
					.addAnnotations(as), this);
				} else {
					bugAccumulator.accumulateBug(
							new BugInstance(this, "DMI_INVOKING_TOSTRING_ON_ANONYMOUS_ARRAY", NORMAL_PRIORITY)
					.addClassAndMethod(this), this);
				}
			}
		}

		if (isTigerOrHigher) {
			if (previousMethodInvocation != null && prevOpCode == INVOKESPECIAL && seen == INVOKEVIRTUAL) {
				String classNameForPreviousMethod = previousMethodInvocation.getClassName();
				String classNameForThisMethod = getClassConstantOperand();
				if (classNameForPreviousMethod.startsWith("java.lang.") 
						&& classNameForPreviousMethod.equals(classNameForThisMethod.replace('/','.'))
						&& getNameConstantOperand().endsWith("Value")
						&& getSigConstantOperand().length() == 3) {
					if (getSigConstantOperand().charAt(2) == previousMethodInvocation.getSignature().charAt(1))
						bugAccumulator.accumulateBug(new BugInstance(this, "BX_BOXING_IMMEDIATELY_UNBOXED", NORMAL_PRIORITY)
						.addClassAndMethod(this), this);
						
					else 
						bugAccumulator.accumulateBug(new BugInstance(this, "BX_BOXING_IMMEDIATELY_UNBOXED_TO_PERFORM_COERCION", NORMAL_PRIORITY)
						.addClassAndMethod(this), this);
						
					ternaryConversionState = 1;
				} else ternaryConversionState = 0;

			} else if (seen == INVOKEVIRTUAL) {
				if (getClassConstantOperand().startsWith("java/lang") && getNameConstantOperand().endsWith("Value") && getSigConstantOperand().length() == 3)
					ternaryConversionState = 1;
				else ternaryConversionState = 0;
			}else if (ternaryConversionState == 1) {
				if (I2L <= seen && seen <= I2S) 
					ternaryConversionState = 2;
				else ternaryConversionState = 0;
			}
			else if (ternaryConversionState == 2) {
				ternaryConversionState = 0;
				if (seen == GOTO) 
					bugReporter.reportBug(new BugInstance(this, "BX_UNBOXED_AND_COERCED_FOR_TERNARY_OPERATOR", NORMAL_PRIORITY)
					.addClassAndMethod(this)
					.addSourceLine(this));
			}
		}

		if (seen == INVOKESTATIC)
			if ((getNameConstantOperand().startsWith("assert") || getNameConstantOperand().startsWith("fail")) && getMethodName().equals("run")
					&& implementsRunnable(getThisClass())) {
				try {
					 int size1 = Util.getSizeOfSurroundingTryBlock(getConstantPool(), getMethod().getCode(),
                    								"java/lang/Throwable", getPC());
					int size2 = Util.getSizeOfSurroundingTryBlock(getConstantPool(), getMethod().getCode(),
                    										"java/lang/Error", getPC());
					int size3 = Util.getSizeOfSurroundingTryBlock(getConstantPool(), getMethod().getCode(),
                    												"java/lang/AssertionFailureError", getPC());
					int size = Math.min(Math.min( size1, size2), size3);
					 if (size == Integer.MAX_VALUE) {
					JavaClass targetClass = AnalysisContext.currentAnalysisContext().lookupClass(getClassConstantOperand().replace('/', '.'));
					if (targetClass.getSuperclassName().startsWith("junit")) {
						bugAccumulator.accumulateBug(new BugInstance(this, "IJU_ASSERT_METHOD_INVOKED_FROM_RUN_METHOD", NORMAL_PRIORITY)
						.addClassAndMethod(this), this);
						

					}
					 }
				} catch (ClassNotFoundException e) {
					AnalysisContext.reportMissingClass(e);
				}

			}
		if (seen == INVOKESPECIAL && getClassConstantOperand().startsWith("java/lang/")  && getNameConstantOperand().equals("<init>")
				&& getSigConstantOperand().length() == 4
		) 

			previousMethodInvocation = XFactory.createReferencedXMethod(this);
		else if (seen == INVOKESTATIC && getClassConstantOperand().startsWith("java/lang/")  
				&& getNameConstantOperand().equals("valueOf")
				&& getSigConstantOperand().length() == 4) 
			previousMethodInvocation = XFactory.createReferencedXMethod(this);
		else previousMethodInvocation = null;
		prevOpCode = seen;

		}
	boolean implementsRunnable(JavaClass obj) {
		if (obj.getSuperclassName().equals("java.lang.Thread")) return true;
		for(String s : obj.getInterfaceNames())
			if (s.equals("java.lang.Runnable")) return true;
		return false;
	}

}
