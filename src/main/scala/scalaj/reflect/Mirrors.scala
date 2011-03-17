package scalaj.reflect

import tools.scalap.scalax.rules.scalasig.{Type => SymType, _}
import java.lang.Class
import java.lang.reflect.{Type => JType}

import Collatable._
import reflect.{Manifest, NoManifest}

/**
 * Mirrors symbols; anything with a name that can be referenced elsewhere
 */
object Mirror {
  import NameHelpers._
  val cache = collection.mutable.Map.empty[Symbol, Mirror]

  private def generateFor(sym: Symbol): Mirror = sym match {
    case o: ObjectSymbol => ObjectMirror(o)
    case c: ClassSymbol if !isRefinementClass(c) && !c.isModule => ClassMirror(c)
    case m: MethodSymbol => MethodMirror(m)
//    case t: TypeSymbol if !t.isParam && !t.name.matches("_\\$\\d+")=> error("not implemented")
    case x => RawSymbolMirror(x)
  }

  def ofSymbol(sym: Symbol): Mirror = cache.getOrElseUpdate(sym, generateFor(sym))

  def ofClass[T: ClassManifest]: Option[ClassMirror] = {
    val tpe = classManifest[T].erasure
    val sig = AutographBook.sigFromType(tpe)

    val classes = sig map { _.topLevelClasses }
    val sym = classes flatMap { _ find (_.name == tpe.getSimpleName) }
    sym map {Mirror ofSymbol} collect { case cm: ClassMirror => cm}
  }

  def ofClass[T](clazz: Class[T]): Option[ClassMirror] = {
    val sig = AutographBook.sigFromType(clazz)

    val classes = sig map { _.topLevelClasses }
    val sym = classes flatMap { _ find (_.name == clazz.getSimpleName) }
    sym map {Mirror ofSymbol} collect { case cm: ClassMirror => cm}
  }

  def ofObject[T <: AnyRef with Singleton](obj: T): Option[ObjectMirror] = {
    val tpe = obj.getClass
    val sig = AutographBook.sigFromType(tpe)

    val objects = sig map { _.topLevelObjects }
    val sym = objects flatMap { _ find (_.name == tpe.getName) }
    sym map {Mirror ofSymbol} collect { case cm: ObjectMirror => cm}
  }

}

sealed trait Mirror
sealed trait NamedMirror extends Mirror { def name: String }


////////////////////////
// TYPE MIRRORS
//

object TypeMirror {
  def apply(tpe: SymType) = tpe match {
//    case mt: MethodType => MethodMirror(mt)
    case trt: TypeRefType => TypeRefMirror(trt)
    case st: SingleType => SingleTypeMirror(st)
    case pt: PolyType => PolyTypeMirror(pt)
    case x => RawTypeMirror(x)
  }
}

sealed trait TypeMirror extends Mirror {
  def tpe: SymType
  def toManifest[T <: AnyRef]: Manifest[T]
  override def toString = "<<symbol type:" + tpe.getClass.getName + ">>"
}

case class RawTypeMirror(tpe: SymType) extends TypeMirror {
  def toManifest[T <: AnyRef]: Manifest[T] = tpe match {
    case ThisType(symbol) => error("todo - ThisType manifest")
    case SingleType(typeRef, symbol) => error("todo - SingleType manifest")
    case ConstantType(constant) => error("todo - ConstantType manifest")
    //case TypeRefType(prefix, symbol, typeArgs) => ...
    case TypeBoundsType(lower, upper) =>
      val lbound = TypeMirror(lower).toManifest
      val ubound = TypeMirror(upper).toManifest
      Manifest.wildcardType(lbound, ubound)
    case RefinedType(classSym, typeRefs) => error("todo - RefinedType manifest")
    case ClassInfoType(symbol, typeRefs) =>
      //todo: check for poly
      Manifest.classType(Class.forName(symbol.path))
      //error("todo - ClassInfoType manifest")
    case ClassInfoTypeWithCons(symbol, typeRefs, cons) => error("todo - ClassInfoTypeWithCons manifest")
    case MethodType(resultType, paramSymbols) => error("todo - MethodType manifest")
    //case PolyType(typeRef, symbols) => error("todo")
    case PolyTypeWithCons(typeRef, symbols, cons) => error("todo - PolyTypeWithCons manifest")
    case ImplicitMethodType(resultType, paramSymbols) => error("todo - ImplicitMethodType manifest")
    case AnnotatedType(typeRef, attribTreeRefs) => error("todo - AnnotatedType manifest")
    case AnnotatedWithSelfType(typeRef, symbol, attribTreeRefs) => error("todo - AnnotatedWithSelfType manifest")
    case DeBruijnIndexType(typeLevel, typeIndex) => error("todo - DeBruijnIndexType manifest")
    case ExistentialType(typeRef, symbols) => error("todo - ExistentialType manifest")
  }
}

case class SingleTypeMirror(tpe: SingleType) extends TypeMirror with NamedMirror {
//  println("SingleTypeMirror for type " + tpe + " with symbol " + tpe.symbol + " of class " + tpe.symbol.getClass)
  val name = tpe.symbol.name
  val path = tpe.symbol.path
  override def toString = name
  def toManifest[T <: AnyRef]: Manifest[T] = {
    println("fetching Manifest for: " + path)
    Manifest.classType(Class.forName(path))
  }
}

case class TypeRefMirror(tpe: TypeRefType) extends TypeMirror with NamedMirror {
  val name = tpe.symbol.name
  val path = tpe.symbol.path
  override def toString = name
  def toManifest[T <: AnyRef]: Manifest[T] = {
    println("fetching Java type for: " + path)
    path match {
      case "scala.Nothing" => Manifest.Nothing.asInstanceOf[Manifest[T]]
      case "scala.Any" => Manifest.Any.asInstanceOf[Manifest[T]]
      case _ => Manifest.classType(Class.forName(path))
    }
  }

}

case class PolyTypeMirror(tpe: PolyType) extends TypeMirror {
  val typeRef = TypeMirror(tpe.typeRef)
  val rawSymbols = tpe.symbols
  val symbols = rawSymbols map { SymbolMirror(_) }
  override def toString = symbols.mkString("[", ", ", "]") + typeRef.toString
  def toManifest[T <: AnyRef]: Manifest[T] = {
    val symbolTypes = symbols collect {
      case sym: TypedSymbolMirror => sym.symType
    }
    if (symbolTypes.size < symbols.size) {
      println("untyped symbol in PolyType signature")
    }

    val symbolManifests = symbolTypes map {_.toManifest}

    val rawClass = tpe.typeRef match {
      case cit: ClassInfoType =>
        Class.forName(cit.symbol.path).asInstanceOf[Class[T]]
      case _ => error("can only extract a manifest from a PolyType wrapping a ClassInfoType, found " + tpe.typeRef)
    }
    Manifest.classType(rawClass, symbolManifests.head, symbolManifests.tail: _*)
  }
}

case class RefinedTypeMirror(underlying: TypeMirror, refinedTypes: List[Manifest]) extends TypeMirror {
  def tpe = underlying.tpe
  def toManifest[T <: AnyRef]: Manifest[T] =
    Manifest.classType(underlying.toManifest.erasure, refinedTypes.head, refinedTypes.tail)

}

////////////////////////
// SYMBOL MIRRORS
//

sealed trait MemberMirror extends NamedMirror { def isPrivate: Boolean }

/**
 * A symbol is ANY atomic entity with a name, such as:
 * * T
 * * Int
 * * someProperty
 * * aMethod
 * * MyClass
 * * ...
 */
object SymbolMirror {
  def apply(sym: Symbol): NamedMirror = sym match {
    case m: MethodSymbol => MethodMirror(m)
    case t: TypeSymbol => TypeSymbolMirror(t)
    case x => RawSymbolMirror(x)
  }
}

sealed trait SymbolMirror extends NamedMirror {
  def sym: Symbol
  def name = sym.name
  override def toString = "<<symbol " + name + ": " + sym.getClass.getName + ">>"
}

sealed trait TypedSymbolMirror extends SymbolMirror {
  def sym: SymbolInfoSymbol
  def symType = TypeMirror(sym.infoType)
  def manifest[T <: AnyRef] = symType.toManifest[T]
  def typeParams: Seq[Manifest[_]] = manifest.typeArguments
}

sealed trait Reifiable[Repr <: Reifiable[_]] {
  self: TypedSymbolMirror =>
  def reify(reifiedParams: Manifest[_]*) : Repr
}

case class RawSymbolMirror(sym: Symbol) extends SymbolMirror

case class TypeSymbolMirror(sym: TypeSymbol) extends TypedSymbolMirror {
  override def toString = name
}

/**
 * Anything that can hold *publicly visible* members.
 * "members" being classes, defs, vals, etc.
 */
abstract class MemberContainerMirror extends SymbolMirror {
  def memberSymbols: Seq[Symbol]

  lazy val typeAliases = memberSymbols collect { case als: AliasSymbol => RawSymbolMirror(als) }

  lazy val allMethodSymbols = memberSymbols collect { case ms @ MethodSymbol(_,_) => ms }
  lazy val reallyTrulyAllMethods = allMethodSymbols map { MethodMirror(_) }
  lazy val allNaturalMethods = reallyTrulyAllMethods filterNot { m => m.isSynthetic || m.isLocal }
  lazy val allSyntheticMethods = reallyTrulyAllMethods filter { m => m.isSynthetic || m.isLocal }

  lazy val allDefs = allNaturalMethods filterNot { m => m.isAccessor || m.isConstructor }
  lazy val publicDefs = allDefs filterNot { _.isPrivate }
  lazy val privateDefs = allDefs filter { _.isPrivate }

  lazy val allAccessors = allNaturalMethods filter { _.isAccessor } collect {
    case m if !(m.name endsWith "_$eq") =>
      val setter = allNaturalMethods find (_.name == m.name + "_$eq")
      setter map {VarMirror(m,_)} getOrElse ValMirror(m)
  }

  lazy val publicAccessors = allAccessors filterNot (_.isPrivate)

  lazy val vals = allAccessors collect {case v: ValMirror => v}
  lazy val vars = allAccessors collect {case v: VarMirror => v}

  //lazy val publicMembers: Seq[NamedMirror] =
}

case class ClassMirror(sym: ClassSymbol) extends MemberContainerMirror with TypedSymbolMirror with Reifiable[ClassMirror] {
  val memberSymbols = sym.children

  val parent = sym.parent
  val qualifiedName = (parent map {_.path + "."} getOrElse "") + sym.name
  lazy val javaClass = Class.forName(qualifiedName)

  def reify(reifiedParams: Manifest[_]*) : ClassMirror = error("not impl")

  lazy val constructors = allNaturalMethods filter {_.isConstructor} map {ConstructorMirror(this,_)}

  override def toString = "class " + name
  //def methods: Seq[MethodMirror] =
}

case class ObjectMirror(sym: ObjectSymbol) extends MemberContainerMirror {
  val TypeRefType(prefix, classSymbol: ClassSymbol, typeArgs) = sym.infoType
  val memberSymbols = classSymbol.children
  override def toString = "object " + name
}

/**
 * Anything that takes parameters: Constructors, Setters and Methods
 */
case class MethodMirror(val sym: MethodSymbol) extends SymbolMirror with MemberMirror {
  val isSynthetic = sym.isSynthetic
  val isAccessor = sym.isAccessor
  val isLocal = sym.isLocal
  val isPrivate = sym.isPrivate
  val isConstructor = name == NameHelpers.CONSTRUCTOR_NAME

  val rawType = sym.infoType
  val (refType, typeParamSymbols) = rawType match {
    case PolyType(refType, typeParamSymbols) => (refType, typeParamSymbols)
    case x => (x, Seq.empty[TypeSymbol])
  }

  val typeParams = typeParamSymbols map TypeSymbolMirror.apply

  private[this] val returnChain = Iterator.iterate(Some(refType): Option[SymType]){
    case Some(MethodType(rt, _))          => Some(rt)
    case Some(ImplicitMethodType(rt, _))  => Some(rt)
    case _                                => None
  } takeWhile (None !=) map (_.get) map {
    case mt: MethodType            => ExplicitParamBlockMirror(mt)
    case imt: ImplicitMethodType   => ImplicitParamBlockMirror(imt)
    case x                         => TypeMirror(x)
  } toList

  val paramBlocks = returnChain collect { case pbm: ParamBlockMirror => pbm }
  val returnType = returnChain.last

  def flatParams = paramBlocks flatMap (_.params)

  override def toString = {
    val prefix = (
      Some("SYN").filter(_ => isSynthetic) ::
      Some("ACC").filter(_ => isAccessor) ::
      Some("CTOR").filter(_ => isConstructor) ::
      Some("LCL").filter(_ => isLocal) ::
      Nil).flatten mkString " "

    val typeParamsStr = if (typeParams.isEmpty) "" else typeParams.mkString("[",", ","]")
    val paramBlocksStr = paramBlocks mkString ""
    prefix + "method " + name + typeParamsStr + paramBlocks.mkString("") + ": " + returnType.toString
  }
  // TODO: def invoke(args: Object*)
}

//TODO: DefMirror?, ConstructorMirror?
/**
 * A val or a var, also (potentially) bean properties
 */

sealed trait PropertyMirror extends MemberMirror {
  def isGettable: Boolean
  def isSettable: Boolean
}

case class ValMirror(getter: MethodMirror) extends PropertyMirror {
  val name = getter.name
  def isPrivate = getter.isPrivate
  val isGettable = true
  val isSettable = false
}

case class VarMirror(getter: MethodMirror, setter: MethodMirror) extends PropertyMirror {
  val name = getter.name
  def isPrivate = getter.isPrivate
  val isGettable = true
  val isSettable = true
}

case class ConstructorMirror(clazz: ClassMirror, method: MethodMirror) extends MemberMirror {
  def isPrivate = method.isPrivate
  def name = "this"
  method.flatParams map (_.name)
  //def toJavaConstructor = clazz.javaClass.getConstructor()
}

abstract class ParamBlockMirror(paramSymbols: Seq[Symbol]) extends Mirror {
  val params = paramSymbols map {
    case ms: MethodSymbol => ParamMirror(ms)
    case x => error("Don't know what to do with a param that isn't a MethodSymbol!")
  }
}

case class ExplicitParamBlockMirror(val tpe: MethodType) extends ParamBlockMirror(tpe.paramSymbols) {
  override def toString = params.mkString("(", ", ", ")")
}
case class ImplicitParamBlockMirror(val tpe: ImplicitMethodType) extends ParamBlockMirror(tpe.paramSymbols) {
  override def toString = params.mkString("(implicit ", ", ", ")")
}

case class ParamMirror(sym: MethodSymbol) extends TypedSymbolMirror {
  override def toString = name + ": " + symType.toString
}




/**
 * Anything that takes a type. Some classes, methods and aliases
 */
trait TypeParamaterizedMirror extends Mirror {
  def typeParams: Seq[Object]
}




/**
 * Anything that has a constructor.
 * Basically just classes at the moment.
 * (maybe traits in the future, who knows?)
 */
//trait ConstructableMirror extends Mirror {
//  def primaryConstructor: MethodMirror
//  def secondaryConstructors: Seq[MethodMirror]
//  def allConstructors = primaryConstructor +: secondaryConstructors
//}





