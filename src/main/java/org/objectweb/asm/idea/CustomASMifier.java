/***
 * ASM: a very small and fast Java bytecode manipulation framework
 * Copyright (c) 2000-2011 INRIA, France Telecom
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in the
 *    documentation and/or other materials provided with the distribution.
 * 3. Neither the name of the copyright holders nor the names of its
 *    contributors may be used to endorse or promote products derived from
 *    this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE
 * ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE
 * LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS
 * INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN
 * CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF
 * THE POSSIBILITY OF SUCH DAMAGE.
 */
package org.objectweb.asm.idea;

import org.objectweb.asm.Attribute;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ConstantDynamic;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.TypePath;
import org.objectweb.asm.util.ASMifier;
import org.objectweb.asm.util.ASMifierSupport;
import org.objectweb.asm.util.Printer;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * A {@link Printer} that prints the ASM code to generate the classes if visits.
 * <p>
 * Customised to prefix opcodes with Opcodes. to allow use in files where static import is not used for the constants
 *
 * @author Eric Bruneton
 * @author Thiakil (modified from OW file October 2021)
 */
// DontCheck(AbbreviationAsWordInName): can't be renamed (for backward binary compatibility).
public final class CustomASMifier extends Printer{
	
	/**
	 * The help message shown when command line arguments are incorrect.
	 */
	private static final String USAGE =
		"Prints the ASM code to generate the given class.\n"
		+ "Usage: ASMifier [-nodebug] <fully qualified class name or class file name>";
	
	/**
	 * A pseudo access flag used to distinguish class access flags.
	 */
	private static final int ACCESS_CLASS = 0x40000;
	
	/**
	 * A pseudo access flag used to distinguish field access flags.
	 */
	private static final int ACCESS_FIELD = 0x80000;
	
	/**
	 * A pseudo access flag used to distinguish inner class flags.
	 */
	private static final int ACCESS_INNER = 0x100000;
	
	/**
	 * A pseudo access flag used to distinguish module requires / exports flags.
	 */
	private static final int ACCESS_MODULE = 0x200000;
	
	private static final String ANNOTATION_VISITOR  = "annotationVisitor";
	private static final String ANNOTATION_VISITOR0 = "annotationVisitor0 = ";
	private static final String COMMA               = "\", \"";
	private static final String END_ARRAY           = " });\n";
	private static final String END_PARAMETERS      = ");\n\n";
	private static final String NEW_OBJECT_ARRAY    = ", new Object[] {";
	private static final String VISIT_END           = ".visitEnd();\n";
	
	private static final List<String> FRAME_TYPES = List.of(
		"Opcodes.TOP",
		"Opcodes.INTEGER",
		"Opcodes.FLOAT",
		"Opcodes.DOUBLE",
		"Opcodes.LONG",
		"Opcodes.NULL",
		"Opcodes.UNINITIALIZED_THIS"
	);
	
	private static final Map<Integer, String> CLASS_VERSIONS =
		Arrays.stream(Opcodes.class.getFields())
		      .filter(f -> f.getName().startsWith("V") && f.getType() == int.class)
		      .map(f -> {
			      try{
				      return Map.entry((Integer)f.get(null), f.getName());
			      }catch(IllegalAccessException e){ throw new RuntimeException(e); }
		      })
		      .filter(e -> e.getKey()>0)
		      .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
	
	
	/**
	 * The name of the visitor variable in the produced code.
	 */
	private final String name;
	
	/**
	 * The identifier of the annotation visitor variable in the produced code.
	 */
	private final int id;
	
	/**
	 * The name of the Label variables in the produced code.
	 */
	private final Map<Label, String> labelNames = new HashMap<>();
	
	/**
	 * Constructs a new {@link CustomASMifier}. <i>Subclasses must not use this constructor</i>. Instead,
	 * they must use the {@link #CustomASMifier(int, String, int)} version.
	 *
	 * @throws IllegalStateException If a subclass calls this constructor.
	 */
	public CustomASMifier(){
		this(/* latest api = */ Opcodes.ASM9, "classWriter", 0);
	}
	
	/**
	 * Constructs a new {@link CustomASMifier}.
	 *
	 * @param api                 the ASM API version implemented by this class. Must be one of {@link Opcodes#ASM4},
	 *                            {@link Opcodes#ASM5}, {@link Opcodes#ASM6}, {@link Opcodes#ASM7}, {@link Opcodes#ASM8} or
	 *                            {@link Opcodes#ASM9}.
	 * @param visitorVariableName the name of the visitor variable in the produced code.
	 * @param annotationVisitorId identifier of the annotation visitor variable in the produced code.
	 */
	private CustomASMifier(
		final int api, final String visitorVariableName, final int annotationVisitorId){
		super(api);
		this.name = visitorVariableName;
		this.id = annotationVisitorId;
	}
	
	/**
	 * Prints the ASM source code to generate the given class to the standard output.
	 *
	 * <p>Usage: ASMifier [-nodebug] &lt;binary class name or class file name&gt;
	 *
	 * @param args the command line arguments.
	 * @throws IOException if the class cannot be found, or if an IOException occurs.
	 */
	public static void main(final String[] args) throws IOException{
		var output = new PrintWriter(System.out, true);
		var logger = new PrintWriter(System.err, true);
		if(args.length<1
		   || args.length>2
		   || ((args[0].equals("-debug") || args[0].equals("-nodebug")) && args.length != 2)){
			logger.println(USAGE);
			return;
		}
		
		var traceClassVisitor = new TraceClassVisitor(null, new CustomASMifier(), output);
		
		String className;
		int    parsingOptions;
		if(args[0].equals("-nodebug")){
			className = args[1];
			parsingOptions = ClassReader.SKIP_DEBUG;
		}else{
			className = args[0];
			parsingOptions = 0;
		}
		
		if(className.endsWith(".class")
		   || className.indexOf('\\') != -1
		   || className.indexOf('/') != -1){
			// Can't fix PMD warning for 1.5 compatibility.
			try(InputStream inputStream = new FileInputStream(className)){ // NOPMD(AvoidFileStream)
				new ClassReader(inputStream).accept(traceClassVisitor, parsingOptions);
			}
		}else{
			new ClassReader(className).accept(traceClassVisitor, parsingOptions);
		}
	}
	
	// -----------------------------------------------------------------------------------------------
	// Classes
	// -----------------------------------------------------------------------------------------------
	
	@Override
	public void visit(int version, int access, String name, String signature, String superName, String[] interfaces){
		String simpleName;
		if(name == null) simpleName = "module-info";
		else{
			int lastSlashIndex = name.lastIndexOf('/');
			if(lastSlashIndex == -1){
				simpleName = name;
			}else{
				text.add("package asm." + name.substring(0, lastSlashIndex).replace('/', '.') + ";\n");
				simpleName = name.substring(lastSlashIndex + 1).replaceAll("[-\\(\\)]", "_");
			}
		}
		text.add("import org.objectweb.asm.AnnotationVisitor;\n");
		text.add("import org.objectweb.asm.Attribute;\n");
		text.add("import org.objectweb.asm.ClassReader;\n");
		text.add("import org.objectweb.asm.ClassWriter;\n");
		text.add("import org.objectweb.asm.ConstantDynamic;\n");
		text.add("import org.objectweb.asm.FieldVisitor;\n");
		text.add("import org.objectweb.asm.Handle;\n");
		text.add("import org.objectweb.asm.Label;\n");
		text.add("import org.objectweb.asm.MethodVisitor;\n");
		text.add("import org.objectweb.asm.Opcodes;\n");
		text.add("import org.objectweb.asm.RecordComponentVisitor;\n");
		text.add("import org.objectweb.asm.Type;\n");
		text.add("import org.objectweb.asm.TypePath;\n");
		text.add("public class " + simpleName + "Dump {\n\n");
		text.add("public static byte[] dump () throws Exception {\n\n");
		text.add("ClassWriter classWriter = new ClassWriter(0);\n");
		text.add("FieldVisitor fieldVisitor;\n");
		text.add("RecordComponentVisitor recordComponentVisitor;\n");
		text.add("MethodVisitor methodVisitor;\n");
		text.add("AnnotationVisitor annotationVisitor0;\n\n");
		
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visit(Opcodes.");
		String versionString = CLASS_VERSIONS.get(version);
		if(versionString != null){
			stringBuilder.append(versionString);
		}else{
			stringBuilder.append(version);
		}
		stringBuilder.append(", ");
		appendAccessFlags(access|ACCESS_CLASS);
		stringBuilder.append(", ");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(signature);
		stringBuilder.append(", ");
		appendConstant(superName);
		stringBuilder.append(", ");
		if(interfaces != null && interfaces.length>0){
			stringBuilder.append("new String[] {");
			for(int i = 0; i<interfaces.length; ++i){
				stringBuilder.append(i == 0? " " : ", ");
				appendConstant(interfaces[i]);
			}
			stringBuilder.append(" }");
		}else{
			stringBuilder.append("null");
		}
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitSource(final String file, final String debug){
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visitSource(");
		appendConstant(file);
		stringBuilder.append(", ");
		appendConstant(debug);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public Printer visitModule(final String name, final int flags, final String version){
		stringBuilder.setLength(0);
		stringBuilder.append("ModuleVisitor moduleVisitor = classWriter.visitModule(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendAccessFlags(flags|ACCESS_MODULE);
		stringBuilder.append(", ");
		appendConstant(version);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier("moduleVisitor", 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public void visitNestHost(final String nestHost){
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visitNestHost(");
		appendConstant(nestHost);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitOuterClass(final String owner, final String name, final String descriptor){
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visitOuterClass(");
		appendConstant(owner);
		stringBuilder.append(", ");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public CustomASMifier visitClassAnnotation(final String descriptor, final boolean visible){
		return visitAnnotation(descriptor, visible);
	}
	
	@Override
	public CustomASMifier visitClassTypeAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation(typeRef, typePath, descriptor, visible);
	}
	
	@Override
	public void visitClassAttribute(final Attribute attribute){
		visitAttribute(attribute);
	}
	
	@Override
	public void visitNestMember(final String nestMember){
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visitNestMember(");
		appendConstant(nestMember);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitPermittedSubclass(final String permittedSubclass){
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visitPermittedSubclass(");
		appendConstant(permittedSubclass);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitInnerClass(
		final String name, final String outerName, final String innerName, final int access){
		stringBuilder.setLength(0);
		stringBuilder.append("classWriter.visitInnerClass(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(outerName);
		stringBuilder.append(", ");
		appendConstant(innerName);
		stringBuilder.append(", ");
		appendAccessFlags(access|ACCESS_INNER);
		stringBuilder.append(END_PARAMETERS);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public CustomASMifier visitRecordComponent(
		final String name, final String descriptor, final String signature){
		stringBuilder.setLength(0);
		stringBuilder.append("{\n");
		stringBuilder.append("recordComponentVisitor = classWriter.visitRecordComponent(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		appendConstant(signature);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier("recordComponentVisitor", 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public CustomASMifier visitField(int access, String name, String descriptor, String signature, Object value){
		stringBuilder.setLength(0);
		stringBuilder.append("{\n");
		stringBuilder.append("fieldVisitor = classWriter.visitField(");
		appendAccessFlags(access|ACCESS_FIELD);
		stringBuilder.append(", ");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		appendConstant(signature);
		stringBuilder.append(", ");
		appendConstant(value);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier("fieldVisitor", 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public CustomASMifier visitMethod(int access, String name, String descriptor, String signature, String[] exceptions){
		stringBuilder.setLength(0);
		stringBuilder.append("{\n");
		stringBuilder.append("methodVisitor = classWriter.visitMethod(");
		appendAccessFlags(access);
		stringBuilder.append(", ");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		appendConstant(signature);
		stringBuilder.append(", ");
		if(exceptions != null && exceptions.length>0){
			stringBuilder.append("new String[] {");
			for(int i = 0; i<exceptions.length; ++i){
				stringBuilder.append(i == 0? " " : ", ");
				appendConstant(exceptions[i]);
			}
			stringBuilder.append(" }");
		}else{
			stringBuilder.append("null");
		}
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier("methodVisitor", 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public void visitClassEnd(){
		text.add("classWriter.visitEnd();\n\n");
		text.add("return classWriter.toByteArray();\n");
		text.add("}\n");
		text.add("}\n");
	}
	
	// -----------------------------------------------------------------------------------------------
	// Modules
	// -----------------------------------------------------------------------------------------------
	
	@Override
	public void visitMainClass(final String mainClass){
		stringBuilder.setLength(0);
		stringBuilder.append("moduleVisitor.visitMainClass(");
		appendConstant(mainClass);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitPackage(final String packaze){
		stringBuilder.setLength(0);
		stringBuilder.append("moduleVisitor.visitPackage(");
		appendConstant(packaze);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitRequire(final String module, final int access, final String version){
		stringBuilder.setLength(0);
		stringBuilder.append("moduleVisitor.visitRequire(");
		appendConstant(module);
		stringBuilder.append(", ");
		appendAccessFlags(access|ACCESS_MODULE);
		stringBuilder.append(", ");
		appendConstant(version);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitExport(final String packaze, final int access, final String... modules){
		visitExportOrOpen("moduleVisitor.visitExport(", packaze, access, modules);
	}
	
	@Override
	public void visitOpen(final String packaze, final int access, final String... modules){
		visitExportOrOpen("moduleVisitor.visitOpen(", packaze, access, modules);
	}
	
	private void visitExportOrOpen(
		final String visitMethod, final String packaze, final int access, final String... modules){
		stringBuilder.setLength(0);
		stringBuilder.append(visitMethod);
		appendConstant(packaze);
		stringBuilder.append(", ");
		appendAccessFlags(access|ACCESS_MODULE);
		if(modules != null && modules.length>0){
			stringBuilder.append(", new String[] {");
			for(int i = 0; i<modules.length; ++i){
				stringBuilder.append(i == 0? " " : ", ");
				appendConstant(modules[i]);
			}
			stringBuilder.append(" }");
		}
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitUse(final String service){
		stringBuilder.setLength(0);
		stringBuilder.append("moduleVisitor.visitUse(");
		appendConstant(service);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitProvide(final String service, final String... providers){
		stringBuilder.setLength(0);
		stringBuilder.append("moduleVisitor.visitProvide(");
		appendConstant(service);
		stringBuilder.append(",  new String[] {");
		for(int i = 0; i<providers.length; ++i){
			stringBuilder.append(i == 0? " " : ", ");
			appendConstant(providers[i]);
		}
		stringBuilder.append(END_ARRAY);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitModuleEnd(){
		text.add("moduleVisitor.visitEnd();\n");
	}
	
	// -----------------------------------------------------------------------------------------------
	// Annotations
	// -----------------------------------------------------------------------------------------------
	
	// DontCheck(OverloadMethodsDeclarationOrder): overloads are semantically different.
	@Override
	public void visit(final String name, final Object value){
		stringBuilder.setLength(0);
		stringBuilder.append(ANNOTATION_VISITOR).append(id).append(".visit(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(value);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitEnum(final String name, final String descriptor, final String value){
		stringBuilder.setLength(0);
		stringBuilder.append(ANNOTATION_VISITOR).append(id).append(".visitEnum(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		appendConstant(value);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public CustomASMifier visitAnnotation(final String name, final String descriptor){
		stringBuilder.setLength(0);
		stringBuilder
			.append("{\n")
			.append("AnnotationVisitor annotationVisitor")
			.append(id + 1)
			.append(" = annotationVisitor");
		stringBuilder.append(id).append(".visitAnnotation(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, id + 1);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public CustomASMifier visitArray(final String name){
		stringBuilder.setLength(0);
		stringBuilder.append("{\n");
		stringBuilder
			.append("AnnotationVisitor annotationVisitor")
			.append(id + 1)
			.append(" = annotationVisitor");
		stringBuilder.append(id).append(".visitArray(");
		appendConstant(name);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, id + 1);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public void visitAnnotationEnd(){
		stringBuilder.setLength(0);
		stringBuilder.append(ANNOTATION_VISITOR).append(id).append(VISIT_END);
		text.add(stringBuilder.toString());
	}
	
	// -----------------------------------------------------------------------------------------------
	// Record components
	// -----------------------------------------------------------------------------------------------
	
	@Override
	public CustomASMifier visitRecordComponentAnnotation(final String descriptor, final boolean visible){
		return visitAnnotation(descriptor, visible);
	}
	
	@Override
	public CustomASMifier visitRecordComponentTypeAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation(typeRef, typePath, descriptor, visible);
	}
	
	@Override
	public void visitRecordComponentAttribute(final Attribute attribute){
		visitAttribute(attribute);
	}
	
	@Override
	public void visitRecordComponentEnd(){
		visitMemberEnd();
	}
	
	// -----------------------------------------------------------------------------------------------
	// Fields
	// -----------------------------------------------------------------------------------------------
	
	@Override
	public CustomASMifier visitFieldAnnotation(final String descriptor, final boolean visible){
		return visitAnnotation(descriptor, visible);
	}
	
	@Override
	public CustomASMifier visitFieldTypeAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation(typeRef, typePath, descriptor, visible);
	}
	
	@Override
	public void visitFieldAttribute(final Attribute attribute){
		visitAttribute(attribute);
	}
	
	@Override
	public void visitFieldEnd(){
		visitMemberEnd();
	}
	
	// -----------------------------------------------------------------------------------------------
	// Methods
	// -----------------------------------------------------------------------------------------------
	
	@Override
	public void visitParameter(final String parameterName, final int access){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(".visitParameter(");
		appendString(stringBuilder, parameterName == null? "noNameParm" : parameterName);
		stringBuilder.append(", ");
		appendAccessFlags(access);
		text.add(stringBuilder.append(");\n").toString());
	}
	
	@Override
	public CustomASMifier visitAnnotationDefault(){
		stringBuilder.setLength(0);
		stringBuilder
			.append("{\n")
			.append(ANNOTATION_VISITOR0)
			.append(name)
			.append(".visitAnnotationDefault();\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public CustomASMifier visitMethodAnnotation(final String descriptor, final boolean visible){
		return visitAnnotation(descriptor, visible);
	}
	
	@Override
	public CustomASMifier visitMethodTypeAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation(typeRef, typePath, descriptor, visible);
	}
	
	@Override
	public CustomASMifier visitAnnotableParameterCount(final int parameterCount, final boolean visible){
		stringBuilder.setLength(0);
		stringBuilder
			.append(name)
			.append(".visitAnnotableParameterCount(")
			.append(parameterCount)
			.append(", ")
			.append(visible)
			.append(");\n");
		text.add(stringBuilder.toString());
		return this;
	}
	
	@Override
	public CustomASMifier visitParameterAnnotation(
		final int parameter, final String descriptor, final boolean visible){
		stringBuilder.setLength(0);
		stringBuilder
			.append("{\n")
			.append(ANNOTATION_VISITOR0)
			.append(name)
			.append(".visitParameterAnnotation(")
			.append(parameter)
			.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ").append(visible).append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public void visitMethodAttribute(final Attribute attribute){
		visitAttribute(attribute);
	}
	
	@Override
	public void visitCode(){
		text.add(name + ".visitCode();\n");
	}
	
	@Override
	public void visitFrame(int type, int numLocal, Object[] local, int numStack, Object[] stack){
		stringBuilder.setLength(0);
		switch(type){
			case Opcodes.F_NEW, Opcodes.F_FULL -> {
				declareFrameTypes(numLocal, local);
				declareFrameTypes(numStack, stack);
				
				stringBuilder.append(name).append(".visitFrame(Opcodes.").append(switch(type){
					case Opcodes.F_NEW -> "F_NEW";
					case Opcodes.F_FULL -> "F_FULL";
					default -> throw new RuntimeException();
				}).append(", ");
				stringBuilder.append(numLocal).append(NEW_OBJECT_ARRAY);
				appendFrameTypes(numLocal, local);
				stringBuilder.append("}, ").append(numStack).append(NEW_OBJECT_ARRAY);
				appendFrameTypes(numStack, stack);
				stringBuilder.append('}');
			}
			case Opcodes.F_APPEND -> {
				declareFrameTypes(numLocal, local);
				stringBuilder
					.append(name)
					.append(".visitFrame(Opcodes.F_APPEND,")
					.append(numLocal)
					.append(NEW_OBJECT_ARRAY);
				appendFrameTypes(numLocal, local);
				stringBuilder.append("}, 0, null");
			}
			case Opcodes.F_CHOP -> stringBuilder.append(name)
			                                    .append(".visitFrame(Opcodes.F_CHOP,")
			                                    .append(numLocal)
			                                    .append(", null, 0, null");
			case Opcodes.F_SAME -> stringBuilder.append(name).append(".visitFrame(Opcodes.F_SAME, 0, null, 0, null");
			case Opcodes.F_SAME1 -> {
				declareFrameTypes(1, stack);
				stringBuilder
					.append(name)
					.append(".visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {");
				appendFrameTypes(1, stack);
				stringBuilder.append('}');
			}
			default -> throw new IllegalArgumentException();
		}
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitInsn(final int opcode){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(".visitInsn(Opcodes.").append(OPCODES[opcode]).append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitIntInsn(final int opcode, final int operand){
		stringBuilder.setLength(0);
		stringBuilder
			.append(name)
			.append(".visitIntInsn(Opcodes.")
			.append(OPCODES[opcode])
			.append(", ")
			.append(opcode == Opcodes.NEWARRAY? TYPES[operand] : Integer.toString(operand))
			.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitVarInsn(final int opcode, final int var){
		stringBuilder.setLength(0);
		stringBuilder
			.append(name)
			.append(".visitVarInsn(Opcodes.")
			.append(OPCODES[opcode])
			.append(", ")
			.append(var)
			.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitTypeInsn(final int opcode, final String type){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(".visitTypeInsn(Opcodes.").append(OPCODES[opcode]).append(", ");
		appendConstant(type);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitFieldInsn(
		final int opcode, final String owner, final String name, final String descriptor){
		stringBuilder.setLength(0);
		stringBuilder.append(this.name).append(".visitFieldInsn(Opcodes.").append(OPCODES[opcode]).append(", ");
		appendConstant(owner);
		stringBuilder.append(", ");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitMethodInsn(
		final int opcode,
		final String owner,
		final String name,
		final String descriptor,
		final boolean isInterface){
		stringBuilder.setLength(0);
		stringBuilder
			.append(this.name)
			.append(".visitMethodInsn(Opcodes.")
			.append(OPCODES[opcode])
			.append(", ");
		appendConstant(owner);
		stringBuilder.append(", ");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		stringBuilder.append(isInterface? "true" : "false");
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitInvokeDynamicInsn(
		final String name,
		final String descriptor,
		final Handle bootstrapMethodHandle,
		final Object... bootstrapMethodArguments){
		stringBuilder.setLength(0);
		stringBuilder.append(this.name).append(".visitInvokeDynamicInsn(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		appendConstant(bootstrapMethodHandle);
		stringBuilder.append(", new Object[]{");
		for(int i = 0; i<bootstrapMethodArguments.length; ++i){
			appendConstant(bootstrapMethodArguments[i]);
			if(i != bootstrapMethodArguments.length - 1){
				stringBuilder.append(", ");
			}
		}
		stringBuilder.append("});\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitJumpInsn(final int opcode, final Label label){
		stringBuilder.setLength(0);
		declareLabel(label);
		stringBuilder.append(name).append(".visitJumpInsn(Opcodes.").append(OPCODES[opcode]).append(", ");
		appendLabel(label);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitLabel(final Label label){
		stringBuilder.setLength(0);
		declareLabel(label);
		stringBuilder.append(name).append(".visitLabel(");
		appendLabel(label);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitLdcInsn(final Object value){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(".visitLdcInsn(");
		appendConstant(value);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitIincInsn(final int var, final int increment){
		stringBuilder.setLength(0);
		stringBuilder
			.append(name)
			.append(".visitIincInsn(")
			.append(var)
			.append(", ")
			.append(increment)
			.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitTableSwitchInsn(
		final int min, final int max, final Label dflt, final Label... labels){
		stringBuilder.setLength(0);
		for(Label label : labels){
			declareLabel(label);
		}
		declareLabel(dflt);
		
		stringBuilder
			.append(name)
			.append(".visitTableSwitchInsn(")
			.append(min)
			.append(", ")
			.append(max)
			.append(", ");
		appendLabel(dflt);
		stringBuilder.append(", new Label[] {");
		for(int i = 0; i<labels.length; ++i){
			stringBuilder.append(i == 0? " " : ", ");
			appendLabel(labels[i]);
		}
		stringBuilder.append(END_ARRAY);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels){
		stringBuilder.setLength(0);
		for(Label label : labels){
			declareLabel(label);
		}
		declareLabel(dflt);
		
		stringBuilder.append(name).append(".visitLookupSwitchInsn(");
		appendLabel(dflt);
		stringBuilder.append(", new int[] {");
		for(int i = 0; i<keys.length; ++i){
			stringBuilder.append(i == 0? " " : ", ").append(keys[i]);
		}
		stringBuilder.append(" }, new Label[] {");
		for(int i = 0; i<labels.length; ++i){
			stringBuilder.append(i == 0? " " : ", ");
			appendLabel(labels[i]);
		}
		stringBuilder.append(END_ARRAY);
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(".visitMultiANewArrayInsn(");
		appendConstant(descriptor);
		stringBuilder.append(", ").append(numDimensions).append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public CustomASMifier visitInsnAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation("visitInsnAnnotation", typeRef, typePath, descriptor, visible);
	}
	
	@Override
	public void visitTryCatchBlock(
		final Label start, final Label end, final Label handler, final String type){
		stringBuilder.setLength(0);
		declareLabel(start);
		declareLabel(end);
		declareLabel(handler);
		stringBuilder.append(name).append(".visitTryCatchBlock(");
		appendLabel(start);
		stringBuilder.append(", ");
		appendLabel(end);
		stringBuilder.append(", ");
		appendLabel(handler);
		stringBuilder.append(", ");
		appendConstant(type);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public CustomASMifier visitTryCatchAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation("visitTryCatchAnnotation", typeRef, typePath, descriptor, visible);
	}
	
	@Override
	public void visitLocalVariable(
		final String name,
		final String descriptor,
		final String signature,
		final Label start,
		final Label end,
		final int index){
		stringBuilder.setLength(0);
		stringBuilder.append(this.name).append(".visitLocalVariable(");
		appendConstant(name);
		stringBuilder.append(", ");
		appendConstant(descriptor);
		stringBuilder.append(", ");
		appendConstant(signature);
		stringBuilder.append(", ");
		appendLabel(start);
		stringBuilder.append(", ");
		appendLabel(end);
		stringBuilder.append(", ").append(index).append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public Printer visitLocalVariableAnnotation(int typeRef, TypePath typePath, Label[] start, Label[] end, int[] index,
	                                            String descriptor, boolean visible){
		stringBuilder.setLength(0);
		stringBuilder
			.append("{\n")
			.append(ANNOTATION_VISITOR0)
			.append(name)
			.append(".visitLocalVariableAnnotation(")
			.append(typeRef);
		if(typePath == null){
			stringBuilder.append(", null, ");
		}else{
			stringBuilder.append(", TypePath.fromString(\"").append(typePath).append("\"), ");
		}
		stringBuilder.append("new Label[] {");
		for(int i = 0; i<start.length; ++i){
			stringBuilder.append(i == 0? " " : ", ");
			appendLabel(start[i]);
		}
		stringBuilder.append(" }, new Label[] {");
		for(int i = 0; i<end.length; ++i){
			stringBuilder.append(i == 0? " " : ", ");
			appendLabel(end[i]);
		}
		stringBuilder.append(" }, new int[] {");
		for(int i = 0; i<index.length; ++i){
			stringBuilder.append(i == 0? " " : ", ").append(index[i]);
		}
		stringBuilder.append(" }, ");
		appendConstant(descriptor);
		stringBuilder.append(", ").append(visible).append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	@Override
	public void visitLineNumber(final int line, final Label start){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(".visitLineNumber(").append(line).append(", ");
		appendLabel(start);
		stringBuilder.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitMaxs(final int maxStack, final int maxLocals){
		stringBuilder.setLength(0);
		stringBuilder
			.append(name)
			.append(".visitMaxs(")
			.append(maxStack)
			.append(", ")
			.append(maxLocals)
			.append(");\n");
		text.add(stringBuilder.toString());
	}
	
	@Override
	public void visitMethodEnd(){
		visitMemberEnd();
	}
	
	// -----------------------------------------------------------------------------------------------
	// Common methods
	// -----------------------------------------------------------------------------------------------
	
	/**
	 * Visits a class, field or method annotation.
	 *
	 * @param descriptor the class descriptor of the annotation class.
	 * @param visible    {@literal true} if the annotation is visible at runtime.
	 * @return a new {@link ASMifier} to visit the annotation values.
	 */
	// DontCheck(OverloadMethodsDeclarationOrder): overloads are semantically different.
	public CustomASMifier visitAnnotation(final String descriptor, final boolean visible){
		stringBuilder.setLength(0);
		stringBuilder
			.append("{\n")
			.append(ANNOTATION_VISITOR0)
			.append(name)
			.append(".visitAnnotation(");
		appendConstant(descriptor);
		stringBuilder.append(", ").append(visible).append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	/**
	 * Visits a class, field or method type annotation.
	 *
	 * @param typeRef    a reference to the annotated type. The sort of this type reference must be
	 *                   {@link org.objectweb.asm.TypeReference#FIELD}. See {@link org.objectweb.asm.TypeReference}.
	 * @param typePath   the path to the annotated type argument, wildcard bound, array element type, or
	 *                   static inner type within 'typeRef'. May be {@literal null} if the annotation targets
	 *                   'typeRef' as a whole.
	 * @param descriptor the class descriptor of the annotation class.
	 * @param visible    {@literal true} if the annotation is visible at runtime.
	 * @return a new {@link ASMifier} to visit the annotation values.
	 */
	public CustomASMifier visitTypeAnnotation(
		final int typeRef, final TypePath typePath, final String descriptor, final boolean visible){
		return visitTypeAnnotation("visitTypeAnnotation", typeRef, typePath, descriptor, visible);
	}
	
	/**
	 * Visits a class, field, method, instruction or try catch block type annotation.
	 *
	 * @param method     the name of the visit method for this type of annotation.
	 * @param typeRef    a reference to the annotated type. The sort of this type reference must be
	 *                   {@link org.objectweb.asm.TypeReference#FIELD}. See {@link org.objectweb.asm.TypeReference}.
	 * @param typePath   the path to the annotated type argument, wildcard bound, array element type, or
	 *                   static inner type within 'typeRef'. May be {@literal null} if the annotation targets
	 *                   'typeRef' as a whole.
	 * @param descriptor the class descriptor of the annotation class.
	 * @param visible    {@literal true} if the annotation is visible at runtime.
	 * @return a new {@link ASMifier} to visit the annotation values.
	 */
	public CustomASMifier visitTypeAnnotation(String method, int typeRef, TypePath typePath, String descriptor, boolean visible){
		stringBuilder.setLength(0);
		stringBuilder
			.append("{\n")
			.append(ANNOTATION_VISITOR0)
			.append(name)
			.append(".")
			.append(method)
			.append("(")
			.append(typeRef);
		if(typePath == null){
			stringBuilder.append(", null, ");
		}else{
			stringBuilder.append(", TypePath.fromString(\"").append(typePath).append("\"), ");
		}
		appendConstant(descriptor);
		stringBuilder.append(", ").append(visible).append(");\n");
		text.add(stringBuilder.toString());
		CustomASMifier asmifier = createASMifier(ANNOTATION_VISITOR, 0);
		text.add(asmifier.getText());
		text.add("}\n");
		return asmifier;
	}
	
	/**
	 * Visit a class, field or method attribute.
	 *
	 * @param attribute an attribute.
	 */
	public void visitAttribute(final Attribute attribute){
		stringBuilder.setLength(0);
		stringBuilder.append("// ATTRIBUTE ").append(attribute.type).append('\n');
		if(attribute instanceof ASMifierSupport){
			stringBuilder.append("{\n");
			((ASMifierSupport)attribute).asmify(stringBuilder, "attribute", labelNames);
			stringBuilder.append(name).append(".visitAttribute(attribute);\n");
			stringBuilder.append("}\n");
		}
		text.add(stringBuilder.toString());
	}
	
	/**
	 * Visits the end of a field, record component or method.
	 */
	private void visitMemberEnd(){
		stringBuilder.setLength(0);
		stringBuilder.append(name).append(VISIT_END);
		text.add(stringBuilder.toString());
	}
	
	// -----------------------------------------------------------------------------------------------
	// Utility methods
	// -----------------------------------------------------------------------------------------------
	
	/**
	 * Constructs a new {@link ASMifier}.
	 *
	 * @param visitorVariableName the name of the visitor variable in the produced code.
	 * @param annotationVisitorId identifier of the annotation visitor variable in the produced code.
	 * @return a new {@link ASMifier}.
	 */
	// DontCheck(AbbreviationAsWordInName): can't be renamed (for backward binary compatibility).
	private CustomASMifier createASMifier(final String visitorVariableName, final int annotationVisitorId){
		return new CustomASMifier(api, visitorVariableName, annotationVisitorId);
	}
	
	/**
	 * Appends a string representation of the given access flags to {@link #stringBuilder}.
	 *
	 * @param flags some access flags.
	 */
	private void appendAccessFlags(int flags){
		var opStrs = new ArrayList<String>();
		
		if((flags&Opcodes.ACC_PUBLIC) != 0) opStrs.add("ACC_PUBLIC");
		if((flags&Opcodes.ACC_PRIVATE) != 0) opStrs.add("ACC_PRIVATE");
		if((flags&Opcodes.ACC_PROTECTED) != 0) opStrs.add("ACC_PROTECTED");
		if((flags&Opcodes.ACC_FINAL) != 0){
			if((flags&ACCESS_MODULE) == 0) opStrs.add("ACC_FINAL");
			else opStrs.add("ACC_TRANSITIVE");
		}
		if((flags&Opcodes.ACC_STATIC) != 0) opStrs.add("ACC_STATIC");
		if((flags&(Opcodes.ACC_SYNCHRONIZED|Opcodes.ACC_SUPER|Opcodes.ACC_TRANSITIVE)) != 0){
			if((flags&ACCESS_CLASS) == 0){
				if((flags&ACCESS_MODULE) == 0) opStrs.add("ACC_SYNCHRONIZED");
				else opStrs.add("ACC_TRANSITIVE");
			}else opStrs.add("ACC_SUPER");
		}
		if((flags&(Opcodes.ACC_VOLATILE|Opcodes.ACC_BRIDGE|Opcodes.ACC_STATIC_PHASE)) != 0){
			if((flags&ACCESS_FIELD) == 0){
				if((flags&ACCESS_MODULE) == 0) opStrs.add("ACC_BRIDGE");
				else opStrs.add("ACC_STATIC_PHASE");
			}else opStrs.add("ACC_VOLATILE");
		}
		if((flags&Opcodes.ACC_VARARGS) != 0 && (flags&(ACCESS_CLASS|ACCESS_FIELD)) == 0) opStrs.add("ACC_VARARGS");
		if((flags&Opcodes.ACC_TRANSIENT) != 0 && (flags&ACCESS_FIELD) != 0) opStrs.add("ACC_TRANSIENT");
		if((flags&Opcodes.ACC_NATIVE) != 0 && (flags&(ACCESS_CLASS|ACCESS_FIELD)) == 0) opStrs.add("ACC_NATIVE");
		if((flags&Opcodes.ACC_ENUM) != 0 && (flags&(ACCESS_CLASS|ACCESS_FIELD|ACCESS_INNER)) != 0) opStrs.add("ACC_ENUM");
		if((flags&Opcodes.ACC_ANNOTATION) != 0 && (flags&(ACCESS_CLASS|ACCESS_INNER)) != 0) opStrs.add("ACC_ANNOTATION");
		if((flags&Opcodes.ACC_ABSTRACT) != 0) opStrs.add("ACC_ABSTRACT");
		if((flags&Opcodes.ACC_INTERFACE) != 0) opStrs.add("ACC_INTERFACE");
		if((flags&Opcodes.ACC_STRICT) != 0) opStrs.add("ACC_STRICT");
		if((flags&Opcodes.ACC_SYNTHETIC) != 0) opStrs.add("ACC_SYNTHETIC");
		if((flags&Opcodes.ACC_DEPRECATED) != 0) opStrs.add("ACC_DEPRECATED");
		if((flags&Opcodes.ACC_RECORD) != 0) opStrs.add("ACC_RECORD");
		if((flags&(Opcodes.ACC_MANDATED|Opcodes.ACC_MODULE)) != 0){
			if((flags&ACCESS_CLASS) == 0) opStrs.add("ACC_MANDATED");
			else opStrs.add("ACC_MODULE");
		}
		
		if(opStrs.isEmpty()) stringBuilder.append('0');
		else stringBuilder.append(opStrs.stream().map(s -> "Opcodes." + s).collect(Collectors.joining(" | ")));
	}
	
	/**
	 * Appends a string representation of the given constant to {@link #stringBuilder}.
	 *
	 * @param value a {@link String}, {@link Type}, {@link Handle}, {@link Byte}, {@link Short},
	 *              {@link Character}, {@link Integer}, {@link Float}, {@link Long} or {@link Double} object,
	 *              or an array of primitive values. May be {@literal null}.
	 */
	private void appendConstant(final Object value){
		if(value == null) stringBuilder.append("null");
		else if(value instanceof String v) appendString(stringBuilder, v);
		else if(value instanceof Type v) stringBuilder.append("Type.getType(\"").append(v.getDescriptor()).append("\")");
		else if(value instanceof Handle handle){
			stringBuilder.append("new Handle(")
			             .append("Opcodes.").append(HANDLE_TAG[handle.getTag()])
			             .append(", \"").append(handle.getOwner())
			             .append(COMMA).append(handle.getName())
			             .append(COMMA).append(handle.getDesc())
			             .append("\", ").append(handle.isInterface())
			             .append(")");
		}else if(value instanceof ConstantDynamic constantDynamic){
			stringBuilder.append("new ConstantDynamic(\"")
			             .append(constantDynamic.getName()).append(COMMA)
			             .append(constantDynamic.getDescriptor()).append("\", ");
			appendConstant(constantDynamic.getBootstrapMethod());
			stringBuilder.append(NEW_OBJECT_ARRAY);
			int bootstrapMethodArgumentCount = constantDynamic.getBootstrapMethodArgumentCount();
			for(int i = 0; i<bootstrapMethodArgumentCount; ++i){
				appendConstant(constantDynamic.getBootstrapMethodArgument(i));
				if(i != bootstrapMethodArgumentCount - 1){
					stringBuilder.append(", ");
				}
			}
			stringBuilder.append("})");
		}else if(value instanceof Byte v) stringBuilder.append("new Byte((byte)").append(v).append(')');
		else if(value instanceof Boolean v) stringBuilder.append(v? "Boolean.TRUE" : "Boolean.FALSE");
		else if(value instanceof Short v) stringBuilder.append("new Short((short)").append(v).append(')');
		else if(value instanceof Character v){
			stringBuilder.append("new Character((char)")
			             .append((int)v)
			             .append(')');
		}else if(value instanceof Integer v) stringBuilder.append("new Integer(").append(v).append(')');
		else if(value instanceof Float v) stringBuilder.append("new Float(\"").append(v).append("\")");
		else if(value instanceof Long v) stringBuilder.append("new Long(").append(v).append("L)");
		else if(value instanceof Double v) stringBuilder.append("new Double(\"").append(v).append("\")");
		else if(value instanceof byte[] byteArray){
			stringBuilder.append(IntStream.range(0, byteArray.length).mapToObj(i -> byteArray[i] + "")
			                              .collect(Collectors.joining(",", "new byte[] {", "}")));
		}else if(value instanceof boolean[] booleanArray){
			stringBuilder.append(IntStream.range(0, booleanArray.length).mapToObj(i -> booleanArray[i] + "")
			                              .collect(Collectors.joining(",", "new boolean[] {", "}")));
		}else if(value instanceof short[] shortArray){
			stringBuilder.append(IntStream.range(0, shortArray.length).mapToObj(i -> "(short)" + shortArray[i])
			                              .collect(Collectors.joining(",", "new short[] {", "}")));
		}else if(value instanceof char[] charArray){
			stringBuilder.append(IntStream.range(0, charArray.length).mapToObj(i -> "(char)" + (int)charArray[i])
			                              .collect(Collectors.joining(",", "new char[] {", "}")));
		}else if(value instanceof int[] intArray){
			stringBuilder.append(Arrays.stream(intArray).mapToObj(j -> j + "")
			                           .collect(Collectors.joining(",", "new int[] {", "}")));
		}else if(value instanceof long[] longArray){
			stringBuilder.append(Arrays.stream(longArray).mapToObj(l -> l + "L")
			                           .collect(Collectors.joining(",", "new long[] {", "}")));
		}else if(value instanceof float[] floatArray){
			stringBuilder.append(IntStream.range(0, floatArray.length).mapToObj(i -> floatArray[i] + "F")
			                              .collect(Collectors.joining(",", "new float[] {", "}")));
		}else if(value instanceof double[] doubleArray){
			stringBuilder.append(Arrays.stream(doubleArray).mapToObj(v -> v + "D")
			                           .collect(Collectors.joining(",", "new double[] {", "}")));
		}else{
			stringBuilder.append("/* UNKNOWN CONSTANT TYPE \"").append(value).append("\"*/");
		}
	}
	
	/**
	 * Calls {@link #declareLabel} for each label in the given stack map frame types.
	 *
	 * @param numTypes   the number of stack map frame types in 'frameTypes'.
	 * @param frameTypes an array of stack map frame types, in the format described in {@link
	 *                   org.objectweb.asm.MethodVisitor#visitFrame}.
	 */
	private void declareFrameTypes(final int numTypes, final Object[] frameTypes){
		for(int i = 0; i<numTypes; ++i){
			if(frameTypes[i] instanceof Label l){
				declareLabel(l);
			}
		}
	}
	
	/**
	 * Appends the given stack map frame types to {@link #stringBuilder}.
	 *
	 * @param numTypes   the number of stack map frame types in 'frameTypes'.
	 * @param frameTypes an array of stack map frame types, in the format described in {@link
	 *                   org.objectweb.asm.MethodVisitor#visitFrame}.
	 */
	private void appendFrameTypes(final int numTypes, final Object[] frameTypes){
		for(int i = 0; i<numTypes; ++i){
			if(i>0){
				stringBuilder.append(", ");
			}
			if(frameTypes[i] instanceof String){
				appendConstant(frameTypes[i]);
			}else if(frameTypes[i] instanceof Integer v){
				stringBuilder.append(FRAME_TYPES.get(v));
			}else{
				appendLabel((Label)frameTypes[i]);
			}
		}
	}
	
	/**
	 * Appends a declaration of the given label to {@link #stringBuilder}. This declaration is of the
	 * form "Label labelXXX = new Label();". Does nothing if the given label has already been
	 * declared.
	 *
	 * @param label a label.
	 */
	private void declareLabel(final Label label){
		if(!labelNames.containsKey(label)){
			var labelName = "label" + labelNames.size();
			labelNames.put(label, labelName);
			stringBuilder.append("Label ").append(labelName).append(" = new Label();\n");
		}
	}
	
	/**
	 * Appends the name of the given label to {@link #stringBuilder}. The given label <i>must</i>
	 * already have a name. One way to ensure this is to always call {@link #declareLabel} before
	 * calling this method.
	 *
	 * @param label a label.
	 */
	private void appendLabel(final Label label){
		stringBuilder.append(labelNames.get(label));
	}
}
