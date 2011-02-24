/*
 * Copyright 2005-2010 LAMP/EPFL
 */
// $Id$

package scala.tools.eclipse
package javaelements

import scala.tools.eclipse.util.{Tracer, Defensive}
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{ Map => JMap }

import scala.concurrent.SyncVar

import org.eclipse.core.internal.filebuffers.SynchronizableDocument
import org.eclipse.core.resources.{ IFile, IResource }
import org.eclipse.core.runtime.IProgressMonitor
import org.eclipse.jdt.core.{
  BufferChangedEvent, CompletionRequestor, IBuffer, IBufferChangedListener, IJavaElement, IJavaModelStatusConstants,
  IProblemRequestor, ITypeRoot, JavaCore, JavaModelException, WorkingCopyOwner }
import org.eclipse.jdt.internal.compiler.env
import org.eclipse.jdt.internal.core.{
  BufferManager, CompilationUnitElementInfo, DefaultWorkingCopyOwner, JavaModelStatus, JavaProject, Openable,
  OpenableElementInfo, SearchableEnvironment }
import org.eclipse.jdt.internal.core.search.matching.{ MatchLocator, PossibleMatch }
import org.eclipse.jdt.internal.ui.javaeditor.DocumentAdapter
import org.eclipse.jface.text.{IRegion, ITextSelection}
import org.eclipse.ui.texteditor.ITextEditor 

import scala.tools.nsc.io.AbstractFile
import scala.tools.nsc.util.{ BatchSourceFile, SourceFile }

import scala.tools.eclipse.contribution.weaving.jdt.{ IScalaCompilationUnit, IScalaWordFinder }

import scala.tools.eclipse.{ ScalaImages, ScalaPlugin, ScalaPresentationCompiler, ScalaSourceIndexer, ScalaWordFinder }
import scala.tools.eclipse.util.ReflectionUtils

trait ScalaCompilationUnit extends Openable with env.ICompilationUnit with ScalaElement with IScalaCompilationUnit with IBufferChangedListener {
  val project = ScalaPlugin.plugin.getScalaProject(getJavaProject.getProject)
  
  private var _changed = new AtomicBoolean(true)

  val file : AbstractFile
  
  def doWithSourceFile(op : (SourceFile, ScalaPresentationCompiler) => Unit) {
    project.withSourceFile(this)(op)(())
  }
  
  def withSourceFile[T](op : (SourceFile, ScalaPresentationCompiler) => T)(orElse: => T = project.defaultOrElse) : T = {
    project.withSourceFile(this)(op)(orElse)
  }
  
  def withSourceFileButNotInMainThread[T](default : => T)(op : (SourceFile, ScalaPresentationCompiler) => T) : T = {
    (Thread.currentThread.getName == "main") match {
      case true => {
        Tracer.printlnWithStack("cancel/default call to withSourceFile in main Thread")
        default
      }
      case false => project.withSourceFile(this)(op)(default)
    }
  }

  override def bufferChanged(e : BufferChangedEvent) {
    if (e.getBuffer.isClosed)
      discard
    else {
      _changed.set(true)
    }

    super.bufferChanged(e)
  }
  
  def discard {
    if (getJavaProject.getProject.isOpen)
      project.withPresentationCompilerIfExists(_.discardSourceFile(this))
  }
  
  override def close {
    discard
    super.close
  }
  
  def createSourceFile : BatchSourceFile = {
    new BatchSourceFile(file, getContents)
  }

  def getProblemRequestor : IProblemRequestor = null

  override def buildStructure(info : OpenableElementInfo, pm : IProgressMonitor, newElements : JMap[_, _], underlyingResource : IResource) : Boolean = {
    Tracer.println("buildStructure : " + underlyingResource)
    //Can freeze UI if in main Thread
    withSourceFile({ (sourceFile, compiler) =>
      import scala.tools.eclipse.util.IDESettings
      
      val contents = this.getContents
      if (IDESettings.compileOnTyping.value && _changed.getAndSet(false)) {
        compiler.askReload(this, contents)
      }

      val unsafeElements = newElements.asInstanceOf[JMap[AnyRef, AnyRef]]
      val sourceLength = sourceFile.length
      //Defensive.tryOrLog[Boolean](false) {
      compiler.withUntypedTree(sourceFile) { tree =>
        compiler.ask { () =>
            new compiler.StructureBuilderTraverser(this, info, unsafeElements, sourceLength).traverse(tree)
        }
      }
      info match {
        case cuei : CompilationUnitElementInfo => 
          cuei.setSourceLength(sourceLength)
          unsafeElements.put(this, info)
        case _ =>
      }
  
      info.setIsStructureKnown(true)
      info.isStructureKnown
    }) (false)
  }
  override def createElementInfo = new CompilationUnitElementInfo
  
  def addToIndexer(indexer : ScalaSourceIndexer) {
    doWithSourceFile { (source, compiler) =>
      compiler.withParseTree(source) { tree =>
        new compiler.IndexBuilderTraverser(indexer).traverse(tree)
      }
    }
  }
  
  def newSearchableEnvironment(workingCopyOwner : WorkingCopyOwner) : SearchableEnvironment = {
    val javaProject = getJavaProject.asInstanceOf[JavaProject]
    javaProject.newSearchableNameEnvironment(workingCopyOwner)
  }

  def newSearchableEnvironment() : SearchableEnvironment =
    newSearchableEnvironment(DefaultWorkingCopyOwner.PRIMARY)
  
  override def getSourceElementAt(pos : Int) : IJavaElement = {
    super.getSourceElementAt(pos) match {
      case smie : ScalaModuleInstanceElement => smie.getParent
      case elem => elem 
    }
  }
    
  override def codeSelect(cu : env.ICompilationUnit, offset : Int, length : Int, workingCopyOwner : WorkingCopyOwner) : Array[IJavaElement] = {
    Array.empty
  }

  def codeComplete
    (cu : env.ICompilationUnit, unitToSkip : env.ICompilationUnit,
     position : Int,  requestor : CompletionRequestor, owner : WorkingCopyOwner, typeRoot : ITypeRoot) {
     codeComplete(cu, unitToSkip, position, requestor, owner, typeRoot, null) 
  }
    
  override def codeComplete
    (cu : env.ICompilationUnit, unitToSkip : env.ICompilationUnit,
     position : Int,  requestor : CompletionRequestor, owner : WorkingCopyOwner, typeRoot : ITypeRoot,
     monitor : IProgressMonitor) {
  }
  
  override def reportMatches(matchLocator : MatchLocator, possibleMatch : PossibleMatch) {
    doWithSourceFile { (sourceFile, compiler) =>
      compiler.withUntypedTree(sourceFile) { tree =>
        compiler.ask { () =>
            compiler.MatchLocator(this, matchLocator, possibleMatch).traverse(tree)
        }
      }
    }
  }
  
  override def createOverrideIndicators(annotationMap : JMap[_, _]) {
    doWithSourceFile { (sourceFile, compiler) =>
      compiler.withUntypedTree(sourceFile) { tree =>
        compiler.ask { () =>
          new compiler.OverrideIndicatorBuilderTraverser(this, annotationMap.asInstanceOf[JMap[AnyRef, AnyRef]]).traverse(tree)
        }
      }
    }
  }
  
  override def getImageDescriptor = {
    Option(getCorrespondingResource) map { file =>
      import ScalaImages.{ SCALA_FILE, EXCLUDED_SCALA_FILE }
      val javaProject = JavaCore.create(project.underlying)
      if (javaProject.isOnClasspath(file)) SCALA_FILE else EXCLUDED_SCALA_FILE
    } orNull
  }
  
  override def getScalaWordFinder() : IScalaWordFinder = ScalaWordFinder
  
  def followReference(editor : ITextEditor, selection : ITextSelection) = {
    val region = new IRegion {
      def getOffset = selection.getOffset
      def getLength = selection.getLength
    }
    new ScalaHyperlinkDetector().detectHyperlinks(editor, region, false) match {
      case Array(hyp) => hyp.open
      case _ =>  
    }
  }
}

object OpenableUtils extends ReflectionUtils {
  private val oClazz = classOf[Openable]
  private val openBufferMethod = getDeclaredMethod(oClazz, "openBuffer", classOf[IProgressMonitor], classOf[AnyRef])
  private val getBufferManagerMethod = getDeclaredMethod(oClazz, "getBufferManager")

  def openBuffer(o : Openable, pm : IProgressMonitor, info : AnyRef) : IBuffer = openBufferMethod.invoke(o, pm, info).asInstanceOf[IBuffer]
  def getBufferManager(o : Openable) : BufferManager = getBufferManagerMethod.invoke(o).asInstanceOf[BufferManager]
}

