/*
 *
 *  Copyright 2011 Cédric Champeau
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 * /
 *
 * Created by IntelliJ IDEA.
 * User: cedric
 * Date: 17/01/11
 * Time: 22:07
 */

package org.objectweb.asm.idea;

import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.commons.Method;
import org.objectweb.asm.idea.config.GroovyCodeStyle;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.Textifier;

import java.util.HashMap;
import java.util.List;

/**
 * A customized trace visitor which outputs code compatible with the Groovy @groovyx.ast.bytecode.Bytecode AST
 * transform.
 *
 * @author Cédric Champeau
 * @author Thiakil (December 2017)
 */
public class GroovifiedTextifier extends Textifier{
	
	private static final String[] GROOVY_DEFAULT_IMPORTS = {
		"java.io.",
		"java.lang.",
		"java.net.",
		"java.util.",
		"groovy.lang.",
		"groovy.util."
	};
	
	private static final String[] ATYPES;
	
	static{
		ATYPES = new String[12];
		String s = "boolean,char,float,double,byte,short,int,long,";
		int    j = 0;
		int    i = 4;
		int    l;
		while((l = s.indexOf(',', j))>0){
			ATYPES[i++] = s.substring(j, l);
			j = l + 1;
		}
	}
	
	private final GroovyCodeStyle codeStyle;
	
	public GroovifiedTextifier(final GroovyCodeStyle codeStyle){
		super(Opcodes.ASM5);
		this.codeStyle = codeStyle;
	}
	
	@Override
	protected Textifier createTextifier(){
		return new GroovifiedMethodTextifier(codeStyle);
	}
	
	@Override
	public Textifier visitMethod(final int access, final String name, final String desc, final String signature, final String[] exceptions){
		stringBuilder.setLength(0);
		stringBuilder.append('\n');
		if((access&Opcodes.ACC_DEPRECATED) != 0){
			stringBuilder.append(tab).append("// @Deprecated\n");
		}
		stringBuilder.append(tab).append("@groovyx.ast.bytecode.Bytecode\n");
		Method method = new Method(name, desc);
		
		stringBuilder.append(tab);
		appendAccess(access);
		if((access&Opcodes.ACC_NATIVE) != 0){
			stringBuilder.append("native ");
		}
		stringBuilder.append(groovyClassName(method.getReturnType().getClassName()));
		stringBuilder.append(' ');
		stringBuilder.append(name);
		stringBuilder.append('(');
		final Type[] argumentTypes = method.getArgumentTypes();
		char         arg           = 'a';
		for(int j = 0, argumentTypesLength = argumentTypes.length; j<argumentTypesLength; j++){
			final Type type = argumentTypes[j];
			stringBuilder.append(groovyClassName(type.getClassName()));
			stringBuilder.append(' ');
			stringBuilder.append(arg);
			if(j<argumentTypesLength - 1) stringBuilder.append(',');
			arg++;
		}
		stringBuilder.append(')');
		if(exceptions != null && exceptions.length>0){
			stringBuilder.append(" throws ");
			for(int i = 0; i<exceptions.length; ++i){
				appendDescriptor(INTERNAL_NAME, exceptions[i].replace('/', '.'));
				if(i<exceptions.length - 1) stringBuilder.append(',');
			}
		}
		stringBuilder.append(" {");
		stringBuilder.append('\n');
		text.add(stringBuilder.toString());
		
		GroovifiedMethodTextifier tcv = (GroovifiedMethodTextifier)createTextifier();
		text.add(tcv.getText());
		text.add("  }\n");
		return tcv;
	}
	
	/**
	 * Appends a string representation of the given access modifiers to {@link #stringBuilder stringBuilder}.
	 *
	 * @param access some access modifiers.
	 */
	private void appendAccess(final int access){
		if((access&Opcodes.ACC_PUBLIC) != 0){
			stringBuilder.append("public ");
		}
		if((access&Opcodes.ACC_PRIVATE) != 0){
			stringBuilder.append("private ");
		}
		if((access&Opcodes.ACC_PROTECTED) != 0){
			stringBuilder.append("protected ");
		}
		if((access&Opcodes.ACC_FINAL) != 0){
			stringBuilder.append("final ");
		}
		if((access&Opcodes.ACC_STATIC) != 0){
			stringBuilder.append("static ");
		}
		if((access&Opcodes.ACC_SYNCHRONIZED) != 0){
			stringBuilder.append("synchronized ");
		}
		if((access&Opcodes.ACC_VOLATILE) != 0){
			stringBuilder.append("volatile ");
		}
		if((access&Opcodes.ACC_TRANSIENT) != 0){
			stringBuilder.append("transient ");
		}
		if((access&Opcodes.ACC_ABSTRACT) != 0){
			stringBuilder.append("abstract ");
		}
		if((access&Opcodes.ACC_STRICT) != 0){
			stringBuilder.append("strictfp ");
		}
		if((access&Opcodes.ACC_ENUM) != 0){
			stringBuilder.append("enum ");
		}
	}
	
	private static String groovyClassName(String className){
		for(String anImport : GROOVY_DEFAULT_IMPORTS){
			if(className.startsWith(anImport)) return className.substring(anImport.length());
		}
		return className;
	}
	
	protected static class GroovifiedMethodTextifier extends Textifier{
		
		private final        GroovyCodeStyle codeStyle;
		private static final Textifier       EMPTY_TEXTIFIER = new Textifier(Opcodes.ASM5){
			@Override
			public List<Object> getText(){
				return List.of();
			}
		};
		
		public GroovifiedMethodTextifier(final GroovyCodeStyle codeStyle){
			super(Opcodes.ASM5);
			this.codeStyle = codeStyle;
		}
		
		private boolean isLegacy(){
			return codeStyle == GroovyCodeStyle.LEGACY;
		}
		
		@Override
		public void visitFrame(final int type, final int nLocal, final Object[] local, final int nStack, final Object[] stack){
			// frames are not supported
		}
		
		@Override
		public void visitLineNumber(final int line, final Label start){
			// line numbers are not necessary
		}
		
		@Override
		public void visitInsn(final int opcode){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append(OPCODES[opcode].toLowerCase()).append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitIntInsn(final int opcode, final int operand){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2)
			             .append(OPCODES[opcode].toLowerCase())
			             .append(' ')
			             .append(opcode == Opcodes.NEWARRAY
			                     ? (isLegacy()? TYPES[operand] : ATYPES[operand])
			                     : Integer.toString(operand))
			             .append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitVarInsn(final int opcode, final int var){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2)
			             .append(OPCODES[opcode].toLowerCase())
			             .append(' ')
			             .append(var)
			             .append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitTypeInsn(final int opcode, final String type){
			stringBuilder.setLength(0);
			final String opcodeStr = OPCODES[opcode];
			stringBuilder.append(tab2).append("NEW".equals(opcodeStr)?
			                                  (isLegacy()? "_new" : "newobject")
			                                                         : "INSTANCEOF".equals(opcodeStr)?
			                                                           (isLegacy()? "_instanceof" : "instance of:") : opcodeStr.toLowerCase()).append(' ');
			if(isLegacy()){
				stringBuilder.append('\'');
				appendDescriptor(INTERNAL_NAME, type);
				stringBuilder.append('\'');
			}else{
				stringBuilder.append(groovyClassName(type.replace('/', '.')));
			}
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitFieldInsn(int opcode, String owner, String name, String desc){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append(OPCODES[opcode].toLowerCase()).append(' ');
			if(isLegacy()){
				stringBuilder.append('\'');
				appendDescriptor(INTERNAL_NAME, owner);
				stringBuilder.append('.').append(name).append("','");
				appendDescriptor(FIELD_DESCRIPTOR, desc);
				stringBuilder.append('\'');
			}else{
				stringBuilder.append(groovyClassName(Type.getObjectType(owner).getClassName()));
				stringBuilder.append('.');
				stringBuilder.append(name);
				stringBuilder.append(" >> ");
				stringBuilder.append(groovyClassName(Type.getObjectType(desc).getClassName()));
			}
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitMethodInsn(int opcode, String owner, String name, String desc){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append(OPCODES[opcode].toLowerCase()).append(' ');
			if(isLegacy()){
				stringBuilder.append('\'');
				appendDescriptor(INTERNAL_NAME, owner);
				stringBuilder.append('.').append(name).append("','");
				appendDescriptor(METHOD_DESCRIPTOR, desc);
				stringBuilder.append('\'');
			}else{
				stringBuilder.append(groovyClassName(Type.getObjectType(owner).getClassName()));
				stringBuilder.append('.');
				if("<init>".equals(name)) stringBuilder.append('"');
				stringBuilder.append(name);
				if("<init>".equals(name)) stringBuilder.append('"');
				stringBuilder.append('(');
				final Type[] types = Type.getArgumentTypes(desc);
				for(int i = 0; i<types.length; i++){
					Type type = types[i];
					stringBuilder.append(groovyClassName(type.getClassName()));
					if(i<types.length - 1) stringBuilder.append(',');
				}
				stringBuilder.append(") >> ");
				stringBuilder.append(groovyClassName(Type.getReturnType(desc).getClassName()));
			}
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitJumpInsn(int opcode, Label label){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append(
				OPCODES[opcode].equals("GOTO")?
				(isLegacy()? "_goto" : "go to:")
				                              : OPCODES[opcode].toLowerCase()).append(' ');
			appendLabel(label);
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public Textifier visitParameterAnnotation(int parameter, String desc, boolean visible){
			return EMPTY_TEXTIFIER;
		}
		
		@Override
		public Textifier visitAnnotation(String desc, boolean visible){
			return EMPTY_TEXTIFIER;
		}
		
		@Override
		public Textifier visitAnnotationDefault(){
			return EMPTY_TEXTIFIER;
		}
		
		/**
		 * Appends the name of the given label to {@link #stringBuilder stringBuilder}. Creates a new label name if the given label does not
		 * yet have one.
		 *
		 * @param l a label.
		 */
		@Override
		protected void appendLabel(Label l){
			if(labelNames == null){
				labelNames = new HashMap<>();
			}
			stringBuilder.append(labelNames.computeIfAbsent(l, k -> "l" + labelNames.size()));
		}
		
		@Override
		public void visitLabel(Label label){
			stringBuilder.setLength(0);
			stringBuilder.append(ltab);
			appendLabel(label);
			if(codeStyle == GroovyCodeStyle.GROOVIFIER_0_2_0) stringBuilder.append(':');
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitLdcInsn(Object cst){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append("ldc ");
			if(cst instanceof String){
				Printer.appendString(stringBuilder, (String)cst);
			}else if(cst instanceof Type){
				stringBuilder.append(((Type)cst).getDescriptor()).append(".class");
			}else if(cst instanceof Float){
				stringBuilder.append(cst).append('f');
			}else if(cst instanceof Double){
				stringBuilder.append(cst).append('d');
			}else if(cst instanceof Integer){
				stringBuilder.append(cst).append('i');
			}else{
				stringBuilder.append(cst);
			}
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
			
		}
		
		@Override
		public void visitIincInsn(int var, int increment){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2)
			             .append("iinc ")
			             .append(var)
			             .append(',')
			             .append(increment)
			             .append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitTableSwitchInsn(int min, int max, Label dflt, Label[] labels){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append("tableswitch(\n");
			for(int i = 0; i<labels.length; ++i){
				stringBuilder.append(tab3).append(min + i).append(": ");
				appendLabel(labels[i]);
				stringBuilder.append(",\n");
			}
			stringBuilder.append(tab3).append("default: ");
			appendLabel(dflt);
			stringBuilder.append(tab2).append("\n)\n");
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitLookupSwitchInsn(Label dflt, int[] keys, Label[] labels){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append("lookupswitch(\n");
			for(int i = 0; i<labels.length; ++i){
				stringBuilder.append(tab3).append(keys[i]).append(": ");
				appendLabel(labels[i]);
				stringBuilder.append(",\n");
			}
			stringBuilder.append(tab3).append("default: ");
			appendLabel(dflt);
			stringBuilder.append(tab2).append("\n)\n");
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitMultiANewArrayInsn(String desc, int dims){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append("multianewarray ");
			if(isLegacy()){
				stringBuilder.append('\'');
				appendDescriptor(FIELD_DESCRIPTOR, desc);
				stringBuilder.append("'");
			}else{
				stringBuilder.append(groovyClassName(Type.getType(desc).getClassName()));
			}
			stringBuilder.append(',').append(dims).append('\n');
			text.add(stringBuilder.toString());
		}
		
		@Override
		public void visitTryCatchBlock(Label start, Label end, Label handler, String type){
			stringBuilder.setLength(0);
			stringBuilder.append(tab2).append("trycatchblock ");
			appendLabel(start);
			stringBuilder.append(',');
			appendLabel(end);
			stringBuilder.append(',');
			appendLabel(handler);
			stringBuilder.append(',');
			if(type != null){
				if(isLegacy()){
					stringBuilder.append('\'');
					appendDescriptor(INTERNAL_NAME, type);
					stringBuilder.append('\'');
				}else{
					stringBuilder.append(groovyClassName(type.replace('/', '.')));
				}
			}else{
				appendDescriptor(INTERNAL_NAME, null);
			}
			stringBuilder.append('\n');
			text.add(stringBuilder.toString());
			
		}
		
		@Override
		public void visitLocalVariable(String name, String desc, String signature, Label start, Label end, int index){ }
		
		@Override
		public void visitMaxs(int maxStack, int maxLocals){ }
	}
}
