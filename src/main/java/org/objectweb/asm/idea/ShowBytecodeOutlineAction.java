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
 */

package org.objectweb.asm.idea;

import com.intellij.ide.highlighter.JavaFileType;
import com.intellij.ide.util.JavaAnonymousClassesHelper;
import com.intellij.openapi.actionSystem.ActionUpdateThread;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.actionSystem.Presentation;
import com.intellij.openapi.application.Application;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.TextEditor;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.roots.CompilerModuleExtension;
import com.intellij.openapi.roots.ProjectFileIndex;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.ui.popup.JBPopupFactory;
import com.intellij.openapi.util.Computable;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.wm.StatusBar;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.WindowManager;
import com.intellij.psi.PsiAnonymousClass;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassOwner;
import com.intellij.psi.PsiCompiledElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiFileFactory;
import com.intellij.psi.PsiManager;
import com.intellij.psi.codeStyle.CodeStyleManager;
import com.intellij.psi.util.ClassUtil;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.ui.LightColors;
import com.intellij.ui.awt.RelativePoint;
import groovyjarjarantlr4.v4.runtime.misc.NotNull;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.idea.config.ASMPluginComponent;
import org.objectweb.asm.util.TraceClassVisitor;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.Semaphore;


/**
 * Given a java file (or any file which generates classes), tries to locate a .class file. If the compilation state is
 * not up to date, performs an automatic compilation of the class. If the .class file can be located, generates bytecode
 * instructions for the class and ASMified code, and displays them into a tool window.
 *
 * @author Cédric Champeau
 * @author Thiakil (December 2017)
 */
public class ShowBytecodeOutlineAction extends AnAction{
	
	@Override
	public void update(final AnActionEvent e){
		final VirtualFile  virtualFile  = e.getData(PlatformDataKeys.VIRTUAL_FILE);
		final Project      project      = e.getData(PlatformDataKeys.PROJECT);
		final Presentation presentation = e.getPresentation();
		if(project == null || virtualFile == null){
			presentation.setEnabled(false);
			return;
		}
		final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
		presentation.setEnabled(psiFile instanceof PsiClassOwner);
	}
	
	@Override
	public @org.jetbrains.annotations.NotNull ActionUpdateThread getActionUpdateThread(){
		return ActionUpdateThread.EDT;
	}
	
	@Override
	public void actionPerformed(AnActionEvent e){
		final VirtualFile virtualFile = e.getData(PlatformDataKeys.VIRTUAL_FILE);
		final Project     project     = e.getData(PlatformDataKeys.PROJECT);
		if(project == null || virtualFile == null) return;
		final PsiFile psiFile = PsiManager.getInstance(project).findFile(virtualFile);
		if(psiFile instanceof PsiClassOwner){
			final Module module = ModuleUtil.findModuleForPsiElement(psiFile);
			if(module == null)
				return;
			final CompilerModuleExtension cme             = CompilerModuleExtension.getInstance(module);
			final CompilerManager         compilerManager = CompilerManager.getInstance(project);
			final VirtualFile[]           files           = {virtualFile};
			if("class".equals(virtualFile.getExtension())){
				runAsmDecode(project, virtualFile);
			}else if(!virtualFile.isInLocalFileSystem() && !virtualFile.isWritable()){
				// probably a source file in a library
				PsiElement el = psiFile.findElementAt(e.getData(CommonDataKeys.CARET).getOffset());
				if(el != null){
					while(el != null && !(el instanceof PsiClass)){
						el = el.getParent();
					}
					if(el != null){
						PsiClass aClass       = (PsiClass)el;
						String   jvmClassName = getJVMClassName(aClass);
						if(jvmClassName != null){
							String           relativePath = jvmClassName.replace('.', '/') + ".class";
							ProjectFileIndex index        = ProjectFileIndex.getInstance(aClass.getProject());
							
							PsiElement originalClass = aClass.getOriginalElement();
							if(aClass instanceof PsiAnonymousClass){
								originalClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class).getOriginalElement();
							}
							if(originalClass instanceof PsiCompiledElement){
								// compiled class; looking for a .class file inside a library
								VirtualFile file = originalClass.getContainingFile().getVirtualFile();
								if(file != null){
									VirtualFile classRoot = index.getClassRootForFile(file);
									if(classRoot != null){
										VirtualFile classFile = classRoot.findFileByRelativePath(relativePath);
										if(classFile != null){
											runAsmDecode(project, classFile);
											return;
										}
									}
								}
							}
						}
					}
				}
				//fallback
				final PsiClass[] psiClasses = ((PsiClassOwner)psiFile).getClasses();
				if(psiClasses.length>0){
					runAsmDecode(project, psiClasses[0].getOriginalElement().getContainingFile().getVirtualFile());
				}
			}else{
				final Application application = ApplicationManager.getApplication();
				application.runWriteAction(() -> FileDocumentManager.getInstance().saveAllDocuments());
				application.executeOnPooledThread(() -> {
					final CompileScope  compileScope      = compilerManager.createFilesCompileScope(files);
					final VirtualFile[] result            = {null};
					final VirtualFile[] outputDirectories = cme == null? null : cme.getOutputRoots(true);
					final Semaphore     semaphore         = new Semaphore(1);
					try{
						semaphore.acquire();
					}catch(InterruptedException e1){
						result[0] = null;
					}
					if(outputDirectories != null && compilerManager.isUpToDate(compileScope)){
						application.invokeLater(() -> {
							result[0] = findClassFile(outputDirectories, psiFile);
							semaphore.release();
						});
					}else{
						application.invokeLater(() -> compilerManager.compile(files, (aborted, errors, warnings, compileContext) -> {
							if(errors == 0){
								VirtualFile[] outputDirectories1 = cme.getOutputRoots(true);
								result[0] = findClassFile(outputDirectories1, psiFile);
							}
							semaphore.release();
						}));
					}
					try{
						semaphore.acquire();
					}catch(InterruptedException e1){
						result[0] = null;
					}
					runAsmDecode(project, result[0]);
				});
			}
		}
	}
	
	public VirtualFile findClassFile(final VirtualFile[] outputDirectories, final PsiFile psiFile){
		return ApplicationManager.getApplication().runReadAction(new Computable<>(){
			public VirtualFile compute(){
				if(outputDirectories != null && psiFile instanceof PsiClassOwner psiJavaFile){
					FileEditor editor      = FileEditorManager.getInstance(psiFile.getProject()).getSelectedEditor(psiFile.getVirtualFile());
					int        caretOffset = editor == null? -1 : ((TextEditor)editor).getEditor().getCaretModel().getOffset();
					if(caretOffset>=0){
						PsiClass psiClass = findClassAtCaret(psiFile, caretOffset);
						if(psiClass != null){
							return getClassFile(psiClass);
						}
					}
					for(PsiClass psiClass : psiJavaFile.getClasses()){
						final VirtualFile file = getClassFile(psiClass);
						if(file != null){
							return file;
						}
					}
				}
				return null;
			}
			
			private VirtualFile getClassFile(@NotNull PsiClass psiClass){
				String jvmClassName = getJVMClassName(psiClass);
				if(jvmClassName == null)
					jvmClassName = "";
				String classFileName = jvmClassName.replace('.', '/') + ".class";
				for(VirtualFile outputDirectory : outputDirectories){
					final VirtualFile file = outputDirectory.findFileByRelativePath(classFileName);
					if(file != null && file.exists()){
						return file;
					}
				}
				return null;
			}
			
			private PsiClass findClassAtCaret(PsiFile psiFile, int caretOffset){
				PsiElement elem = psiFile.findElementAt(caretOffset);
				while(elem != null){
					if(elem instanceof PsiClass){
						return (PsiClass)elem;
					}
					elem = elem.getParent();
				}
				return null;
			}
		});
	}
	
	/**
	 * Reads the .class file, processes it through the ASM TraceVisitor and ASMifier
	 */
	public void runAsmDecode(final Project project, final VirtualFile file){
		if(file == null){
			ApplicationManager.getApplication().invokeLater(() -> ApplicationManager.getApplication().runWriteAction(() -> updateToolWindowContents(project, file, null, null, null)));
			return;
		}
		ApplicationManager.getApplication().executeOnPooledThread(() -> {
			StringWriter stringWriter = new StringWriter();
			ClassVisitor visitor      = new TraceClassVisitor(new PrintWriter(stringWriter));
			ClassReader  reader;
			try{
				file.refresh(false, false);
				reader = new ClassReader(file.contentsToByteArray());
			}catch(IOException e){
				return;
			}
			int                      flags  = 0;
			final ASMPluginComponent config = project.getService(ASMPluginComponent.class);
			if(config.isSkipDebug()) flags = flags|ClassReader.SKIP_DEBUG;
			if(config.isSkipFrames()) flags = flags|ClassReader.SKIP_FRAMES;
			if(config.isExpandFrames()) flags = flags|ClassReader.EXPAND_FRAMES;
			if(config.isSkipCode()) flags = flags|ClassReader.SKIP_CODE;
			
			reader.accept(visitor, flags);
			String byteCodeOutline = stringWriter.toString();
			
			stringWriter.getBuffer().setLength(0);
			reader.accept(new TraceClassVisitor(null, new GroovifiedTextifier(config.getCodeStyle()), new PrintWriter(stringWriter)), ClassReader.SKIP_FRAMES|ClassReader.SKIP_DEBUG);
			String groovified = stringWriter.toString();
			
			stringWriter.getBuffer().setLength(0);
			reader.accept(new TraceClassVisitor(null,
			                                    new CustomASMifier(),
			                                    new PrintWriter(stringWriter)), flags);
			ApplicationManager.getApplication().invokeLater(() ->
				                                                ApplicationManager.getApplication().runWriteAction(() -> {
					                                                PsiFile psiFile = PsiFileFactory.getInstance(project).createFileFromText("asm.java", JavaFileType.INSTANCE, stringWriter.toString());
					                                                CodeStyleManager.getInstance(project).reformatText(psiFile, 0, psiFile.getTextLength());
					                                                
					                                                updateToolWindowContents(project, file, byteCodeOutline, psiFile.getText(), groovified);
				                                                })
			);
		});
	}
	
	/**
	 * Update the contents of the two tabs of the tool window.
	 *
	 * @param project the project instance
	 * @param file    the class file
	 */
	private void updateToolWindowContents(final Project project, final VirtualFile file, String byteCodeOutline, String asmified, String groovified){
		if(file == null){
			BytecodeOutline.getInstance(project).setCode(null, "");
			BytecodeASMified.getInstance(project).setCode(null, "");
			GroovifiedView.getInstance(project).setCode(null, "");
			ApplicationManager.getApplication().invokeLater(() -> {
				Balloon balloon = JBPopupFactory.getInstance()
				                                .createHtmlTextBalloonBuilder(Constants.NO_CLASS_FOUND, null, LightColors.RED, null)
				                                .setHideOnAction(true)
				                                .setHideOnClickOutside(true)
				                                .setHideOnKeyOutside(true)
				                                .createBalloon();
				StatusBar statusBar = WindowManager.getInstance().getStatusBar(project);
				balloon.show(RelativePoint.getCenterOf(statusBar.getComponent()), Balloon.Position.above);
			});
			return;
		}
		
		BytecodeOutline.getInstance(project).setCode(file, byteCodeOutline);
		
		GroovifiedView.getInstance(project).setCode(file, groovified);
		
		BytecodeASMified.getInstance(project).setCode(file, asmified);
		
		ToolWindowManager.getInstance(project).getToolWindow("ASM").activate(null);
	}
	
	private static String getJVMClassName(PsiClass aClass){
		if(!(aClass instanceof PsiAnonymousClass)){
			return ClassUtil.getJVMClassName(aClass);
		}
		
		PsiClass containingClass = PsiTreeUtil.getParentOfType(aClass, PsiClass.class);
		if(containingClass != null){
			return getJVMClassName(containingClass) + JavaAnonymousClassesHelper.getName((PsiAnonymousClass)aClass);
		}
		
		return null;
	}
}
