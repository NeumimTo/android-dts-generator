package com.telerik;

import java.io.*;
import java.lang.reflect.Modifier;
import java.util.*;

import javax.xml.transform.*;
import javax.xml.transform.stream.*;

import org.apache.bcel.classfile.Attribute;
import org.apache.bcel.classfile.ClassParser;
import org.apache.bcel.classfile.Field;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;
import org.apache.bcel.classfile.Signature;
import org.apache.bcel.generic.Type;

import com.sun.codemodel.internal.JVar;
import com.telerik.java.api.*;
import com.telerik.java.api.compiler.model.*;
import com.telerik.java.api.compiler.processor.*;
import com.telerik.java.api.reflection.JavaJarContext;
import com.telerik.javascript.api.*;

/**
 * The main class to verify java files using custom annotation processor. The
 * files to be verified can be supplied to this class as comma-separated
 * argument.
 */
public class Main {

	public static enum Input
	{
		Reflection,
		SourceCode
	}
	
	
	static class DtsWriter extends com.telerik.java.api.bcel.Generator.VisitorCallback
	{
		private int level = 0;
		
		private PrintStream output;
		private StringBuilder sb;
		private String currentFilename;
		private Set<String> references;
		private Set<Method> methods;
		private Set<Field> fields;
		private JavaClass javaClass;
		
		private File outDir;
		
		public DtsWriter() throws FileNotFoundException
		{
			super();
			sb = new StringBuilder();
			references = new HashSet<String>();
			methods = new HashSet<Method>();
			fields = new HashSet<Field>();
			outDir = new File("C:/Work/NativeScript/NativeScript/dtsfiles");
			if (!outDir.exists())
			{
				outDir.mkdirs();
			}
		}

		public void onVisit(Field field)
		{
			fields.add(field);
		}

		public void onVisit(Method method)
		{
			methods.add(method);
		}
		
		public void onEnter(JavaClass javaClass)
		{
			assert this.level == 0;
			assert this.methods.size() == 0;
			assert this.fields.size() == 0;
			assert this.references.size() == 0;
			assert this.javaClass == null;
			
			this.javaClass = javaClass;
			
			String className = javaClass.getClassName();
			//
			String scn = javaClass.getSuperclassName();
			JavaClass baseClass = this.findClass(scn);
			assert baseClass != null : "javaClass=" + javaClass.getClassName() + " scn=" + scn;
			//
			boolean success = createOutput(className.replaceAll("\\$", "\\."));
			assert success;
			
			String packageName = javaClass.getPackageName();
			writePackageHeader(packageName);

			String ident;
			String[] outerClasses = null;
			if (isNested(javaClass))
			{
				int lastDollarSign = className.lastIndexOf("$");
				outerClasses = className.substring(packageName.length() + 1, lastDollarSign).split("\\.");
				for (String outer: outerClasses)
				{
					ident = new String(new char[level++]).replace("\0", "\t");
					println(ident + "export namespace " + outer + " {");
				}
			}

			ident = new String(new char[level++]).replace("\0", "\t");
			
			Attribute[] attributes = javaClass.getAttributes();
			String sig = "";
			for (Attribute attribute : attributes)
			{
			    if (attribute instanceof Signature)
			    {
			        Signature signature = (Signature) attribute;
			        int len = signature.getLength();
			        boolean isGenerics = len > 0;
			        if (isGenerics)
			        {
			        	System.out.println("class=" + javaClass.getClassName() + " signature=" + signature.getSignature());
			        }
			        SignatureParser sigParser = new SignatureParser(signature);
			        sig = sigParser.parse();
			        break;
			    }
			}
			
			String name = getSimpleClassName(javaClass);
			
			if (javaClass.isInterface())
			{
				println(ident + "export interface I" + name + sig + " {");	
			}
			else
			{
				String extendClause = className.equals("java.lang.Object")
									? ""
									: (sig + " extends " + getSupperClassName(javaClass));
				println(ident + "export class " + name + extendClause + " {");
			}
		}
		
		@Override
		public void onLeave(JavaClass javaClass) {
			
			assert this.javaClass != null;
			assert this.javaClass == javaClass;
			
			boolean isInterface = javaClass.isInterface();
			
			boolean writeModifiers = !isInterface;
			
			writesMethods(writeModifiers);
			
			writesInstanceFields(writeModifiers);
			
			if (!isInterface)
			{
				writeStaticFields(writeModifiers);
			}
			
			String ident = new String(new char[--level]).replace("\0", "\t");
			println(ident + "}");
			
			if (isInterface)
			{
				String name = getSimpleClassName(javaClass);
				ident = new String(new char[level]).replace("\0", "\t");
				++this.level;
				println(ident + "export class " + name + " implements I" + name + " {");
				println(ident + "\tpublic constructor(implementation: I" + name + ");");
				writesMethods(true);
				writeStaticFields(true);
				--this.level;
				
				println(ident + "}");
			}

			if (isNested(javaClass))
			{
				String className = javaClass.getClassName();
				int lastDollarSign = className.lastIndexOf("$");
				String packageName = javaClass.getPackageName();
				String[] outerClasses = className.substring(packageName.length() + 1, lastDollarSign).split("\\.");
				for (String outer: outerClasses)
				{
					ident = new String(new char[--level]).replace("\0", "\t");
					println(ident + "}");
				}
			}
			
			writeReferences();
			
			writePackageFooter();
			
			output.println(sb.toString());
			output.flush();
			output.close();
			this.methods.clear();
			this.fields.clear();
			this.references.clear();
			this.javaClass = null;
			
			assert this.level == 0;
			assert this.methods.size() == 0;
			assert this.fields.size() == 0;
			assert this.references.size() == 0;
			assert this.javaClass == null;
		}
		
		private String getSimpleClassName(JavaClass javaClass)
		{
			char delim = isNested(javaClass) ? '$' : '.';
			String className = javaClass.getClassName();
			int idx = className.lastIndexOf(delim);
			String name = className.substring(idx + 1);
			
			return name; 
		}
		
		private boolean isNested(JavaClass javaClass)
		{
			String className = javaClass.getClassName();
			int lastDollarSign = className.lastIndexOf('$');
			boolean isNested = lastDollarSign > 0;
			return isNested;
		}
		
		private Comparator<Method> methodCmp = new Comparator<Method>()
		{
			@Override
			public int compare(Method m1, Method m2) {
				return m1.getName().compareTo(m2.getName());
			}
		};
		
		private Comparator<Field> fieldCmp = new Comparator<Field>()
		{
			@Override
			public int compare(Field f1, Field f2) {
				return f1.getName().compareTo(f2.getName());
			}
		};
		
		private String getMethodFullSignature(Method m)
		{
			String sig = m.getName() + m.getSignature();
			return sig;
		}
		
		private void writesMethods(boolean writeModifiers)
		{
			String ident = new String(new char[level]).replace("\0", "\t");
			
			//
			Set<String> baseMethodNames = new HashSet<String>();
			List<Method> baseMethods = new ArrayList<Method>();
			String scn = this.javaClass.getSuperclassName();
			JavaClass currClass = this.findClass(scn);
			assert currClass != null : "javaClass=" + this.javaClass.getClassName() + " scn=" + scn;
			
			while (true)
			{
				for (Method m: currClass.getMethods())
				{
					if (!m.isSynthetic() && (m.isPublic() || m.isProtected()))
					{
						baseMethods.add(m);
						baseMethodNames.add(m.getName());
					}
				}
				
				if (currClass.getClassName().equals("java.lang.Object"))
					break;
				
				scn = currClass.getSuperclassName();
				JavaClass baseClass = this.findClass(scn);
				assert baseClass != null : "baseClass=" + currClass.getClassName() + " scn=" + scn;
				currClass = baseClass;
			}
			
			Map<String, Method> ms = new HashMap<String, Method>();
			for (Method m: this.methods)
			{
				String currMethodSig = getMethodFullSignature(m);
				if (!ms.containsKey(currMethodSig))
				{
					ms.put(currMethodSig, m);
				}
				String name = m.getName();
				if (baseMethodNames.contains(name))
				{
					for (Method bm: baseMethods)
					{
						if (bm.getName().equals(name))
						{
							String sig = getMethodFullSignature(bm);
							if (!ms.containsKey(sig))
							{
								ms.put(sig, bm);
							}
						}
					}
				}
			}
			
			Method[] arrMethods = new Method[ms.size()];
			ms.values().toArray(arrMethods);
			Arrays.sort(arrMethods, methodCmp);
			
			Set<String> mixedAccessors = new HashSet<String>();
			for (int i=0; i<arrMethods.length; i++)
			{
				Method m1 = arrMethods[i];
				boolean m1Public = Modifier.isPublic(m1.getModifiers());
				for (int j=i+1; j<arrMethods.length; j++)
				{
					Method m2 = arrMethods[j];
					boolean m2Public = Modifier.isPublic(m2.getModifiers());
					if (m1.getName().equals(m2.getName()) && (m1Public != m2Public))
					{
						mixedAccessors.add(m1.getName());
						break;
					}
				}
			}
			
			for (Method m: arrMethods)
			{
				if ((!m.isPublic() && !m.isProtected()) || m.isSynthetic())
				{
					continue;
				}
				
				sb.append(ident);
				if (writeModifiers)
				{
					if (isConstructor(m) || mixedAccessors.contains(m.getName()))
					{
						sb.append("public ");	
					}
					else
					{
						sb.append(m.isPublic() ? "public " : "protected ");
					}
				}
				if (m.isStatic())
				{
					sb.append("static ");
				}
				sb.append(getMethodName(m));
				sb.append("(");
				int idx = 0;
				addReference(m.getReturnType());
				for (Type t: m.getArgumentTypes())
				{
					addReference(t);
					if (idx > 0)
					{
						sb.append(", ");
					}
					sb.append("param");
					sb.append(idx++);
					sb.append(": ");
					sb.append(getTypeScriptTypeFromJavaType(t));
				}
				sb.append(")");
				if (!isConstructor(m))
				{
					sb.append(": ");
					sb.append(getTypeScriptTypeFromJavaType(m.getReturnType()));
				}
				sb.append(";\n");
			}
		}
		
		private void writesInstanceFields(boolean forceWriteModifiers)
		{
			writeFields(forceWriteModifiers, false);
		}
		
		private void writeStaticFields(boolean forceWriteModifiers)
		{
			writeFields(forceWriteModifiers, true);	
		}
		
		private void writeFields(boolean forceWriteModifiers, boolean isStatic)
		{
			assert this.javaClass  != null;
			
			ArrayList<Field> arrFields = new ArrayList<Field>();
			for (Field f: fields)
			{
				if (f.isStatic() == isStatic)
				{
					arrFields.add(f);
				}
			}
			
			writeFields(forceWriteModifiers, arrFields);
		}
		
		private void writeFields(boolean forceWriteModifiers, ArrayList<Field> arrFields)
		{
			arrFields.sort(fieldCmp);
			
			String ident = new String(new char[level]).replace("\0", "\t");
			
			for (Field f: arrFields)
			{
				sb.append(ident);
				if (forceWriteModifiers)
				{
					sb.append("public ");
					if (f.isStatic())
					{
						sb.append("static ");
					}
				}
				sb.append(f.getName());
				sb.append(": ");
				Type t = f.getType();
				addReference(t);
				sb.append(getTypeScriptTypeFromJavaType(t));
				sb.append(";\n");
			}
		}

		private boolean isConstructor(Method m)
		{
			return m.getName().equals("<init>");
		}
		
		private String getMethodName(Method m)
		{
			String name = m.getName();
			
			if (isConstructor(m))
			{
				name = "constructor";
			}
			
			return name;
		}
		
		private String getTypeScriptTypeFromJavaType(Type t)
		{
			String tsType;
			String type = t.getSignature();
			
			switch (type)
			{
			case "V":
				tsType = "void";
				break;
			case "C":
				tsType = "string";
				break;
			case "Z":
				tsType = "boolean";
				break;
			case "B":
			case "S":
			case "I":
			case "J":
			case "F":
			case "D":
				tsType = "number";
				break;
			case "Ljava/lang/CharSequence;":
			case "Ljava/lang/String;":
				tsType = "string";
				break;
			default:
				StringBuilder sb = new StringBuilder();
				convertToTypeScriptType(type, sb);
				tsType = sb.toString();
			}
			
			return tsType;
		}
		
		private void convertToTypeScriptType(String type, StringBuilder tsType)
		{
			switch (type)
			{
			case "C":
				tsType.append("string");
				break;
			case "Z":
				tsType.append("boolean");
				break;
			case "B":
			case "S":
			case "I":
			case "J":
			case "F":
			case "D":
				tsType.append("number");
				break;
			case "java.lang.CharSequence":
			case "java.lang.String":
				tsType.append("string");
				break;
			default:
				if (type.charAt(0) == '[')
				{
					tsType.append("native.Array<");
					convertToTypeScriptType(type.substring(1), tsType);
					tsType.append(">");
				}
				else
				{
					String ts;
					int idx = type.indexOf('<');
					if (idx > 0)
					{
						ts = type.substring(1, idx).replaceAll("/", ".").replaceAll("\\$", "\\.").replaceAll(";", "");
						tsType.append(ts);
					}
					else
					{
						ts = type.substring(1).replaceAll("/", ".").replaceAll("\\$", "\\.").replaceAll(";", "");
						tsType.append(ts);
					}
				}
			}
		}
		
		private boolean createOutput(String className)
		{
			boolean success;
			sb.setLength(0);
			try
			{
				currentFilename = className;
				File file = new File(outDir, className + ".d.ts");
				output = new PrintStream(file);
				success = true;
			}
			catch (FileNotFoundException e)
			{
				success = false;
				e.printStackTrace();
			}
			return success;
		}
		
		private void writePackageHeader(String packageName)
		{
			String[] packages = packageName.split("\\.");
			String ident;
			for (String p: packages)
			{
				ident = new String(new char[level++]).replace("\0", "\t");
				if (level == 1)
				{
					println(ident + "declare namespace " + p + " {");				
				}
				else
				{
					println(ident + "export namespace " + p + " {");
				}
			}
		}
		
		private void writePackageFooter()
		{
			while (level > 0)
			{
				String ident = new String(new char[--level]).replace("\0", "\t");
				println(ident + "}");
			}
		}
		
		private void addReference(Type type)
		{
			String sig = type.getSignature();
		
			if (isPrimitiveType(type) || sig.equals("Ljava/lang/String;"))
			{
				return;
			}
			
			if (this.javaClass.getClassName().equals("java.lang.Object") && sig.equals("Ljava/lang/Object;"))
			{
				return;
			}

			int idx = sig.lastIndexOf('[');
			
			String strippedSig = sig.substring(idx + 1);
			
			if (isPrimitiveType(strippedSig))	
			{
				return;
			}
			
			assert strippedSig.charAt(0) == 'L';
			
			String ref = getFilenameFromType(strippedSig);
			
			if (!ref.equals(this.currentFilename))
			{
				references.add(ref);
			}
		}
		
		private Comparator<String> cmpStrRev = new Comparator<String>()
		{
			@Override
			public int compare(String s1, String s2) {
				return s2.compareTo(s1);
			}
		}; 
		
		private void writeReferences()
		{
			this.references.add("_helpers");
			if (!this.javaClass.getClassName().equals("java.lang.Object"))
			{
				String baseClassName = this.javaClass.getSuperclassName();
				JavaClass baseClass = this.findClass(baseClassName);
				while ((baseClass != null) && !baseClass.isPublic())
				{
					baseClassName = baseClass.getSuperclassName();
					baseClass = this.findClass(baseClassName);
				}
				if ((baseClassName != null) && (baseClassName.length() > 0))
				{
					references.add(baseClassName);
				}
			}
			StringBuilder refs = new StringBuilder();
			String[] arrRefs = new String[references.size()];
			references.toArray(arrRefs);
			Arrays.sort(arrRefs, cmpStrRev);
			for (String refFilename: arrRefs)
			{
				refs.insert(0, "///<reference path=\"./" + refFilename + ".d.ts\" />\n");
			}
			refs.append("\n");
			sb.insert(0, refs.toString());
		}
		
		private String getFilenameFromType(String type)
		{
			String name;
			
			int idx = type.indexOf('<');
			if (idx > 0)
			{
				name = type.substring(1, idx);
			}
			else
			{
				name = type.substring(1);
			}
			
			name = name.replaceAll("/", ".").replaceAll("\\$", "\\.").replaceAll(";", "");
			
			return name;
		}
		
		private boolean isPrimitiveType(Type t)
		{
			String signature = t.getSignature();
			
			boolean isPrimitive = isPrimitiveType(signature);
			
			return isPrimitive;
		}
		
		private boolean isPrimitiveType(String typeName)
		{
			boolean isPrimitive = typeName.equals("V")
				|| typeName.equals("C")
				|| typeName.equals("Z")
				|| typeName.equals("B") || typeName.equals("S") || typeName.equals("I") || typeName.equals("J")
				|| typeName.equals("F") || typeName.equals("D");
			
			return isPrimitive;
		}
		
		private void print(String s)
		{
			sb.append(s);
		}

		private void println(String s)
		{
			print(s);
			sb.append('\n');
		}
		
		private String getSupperClassName(JavaClass javaClass)
		{
			String name = javaClass.getSuperclassName();
			
			File file = new File(javaClass.getFileName());
			
			String[] packageNames = javaClass.getPackageName().split("\\.");
			int len = packageNames.length + 1;
			for (int i=0; i<len; i++)
			{
				file = file.getParentFile();
			}
			
			String[] parts = name.split("\\.");
			for (int i=0; i<parts.length; i++)
			{
				String p = parts[i];
				if (i == (parts.length - 1))
				{
					p += ".class";
				}
				File f = new File(file, p);
				if (!f.exists())
				{
					break;
					
				}
				file = f;
			}
			
			if (file.exists() && file.isFile())
			{
				String fileName = file.getAbsolutePath();
				try
				{
					ClassParser cp = new ClassParser(fileName);
					JavaClass clazz = cp.parse();
					if (clazz.isPublic())
					{
						name = clazz.getClassName();
					}
					else
					{
						name = getSupperClassName(clazz);
					}
				}
				catch (IOException e)
				{
					e.printStackTrace();
				}
			}
			
			return name.replaceAll("\\$", "\\.");
		}
		
		static class SignatureParser
		{
			private final Signature signature;
			
			private final String sig;
			
			private int idx;

			public SignatureParser(Signature signature)
			{
				this.signature = signature;
				this.sig = signature.getSignature();
			}
			
			
			public String parse()
			{
				if (sig.charAt(0) == '<')
				{
					idx = 1;
					
					System.out.print("<");
					{
						readArgList(0);
					}
					System.out.print(">");
					
					System.out.println();
					System.out.println();
				}
				
				// TODO:
				return "";//sig;
			}
			
			private void readArgList(int paramIdx)
			{
				if (paramIdx > 0)
				{
					System.out.print(", ");
				}

				
				readArg();
				
				assert sig.charAt(idx-1) == ';';
				
				char c = sig.charAt(idx);
				if (c != '>')
				{
					readArgList(paramIdx + 1);
				}
			}
			
			private void readArg()
			{
				readId();
				
				assert sig.charAt(idx) == ':';
				
				readExtends();
				
				assert sig.charAt(idx) != ':';
				
				readRefType2();
				
				assert sig.charAt(idx-1) == ';' : "char=" + sig.charAt(idx-1) + ", idx=" +idx;
			}
			
			private void readId()
			{
				char c;
				while (Character.isJavaIdentifierPart((c = sig.charAt(idx))))
				{
					System.out.print(c);
					++idx;
				}
			}
			
			private void readExtends()
			{
				while (sig.charAt(idx) == ':')
				{
					++idx;
				}
				
				System.out.print(" extends ");
			}
			
			private void readRefType2()
			{
				readTypeName();
				
				char c = sig.charAt(idx++);
				if (c == '<')
				{
					System.out.print("<");
					{
						readGenList(0);
					}
					System.out.print(">");
					
					assert ((sig.charAt(idx-1) == ';') || (sig.charAt(idx-1) == '*')) : "char=" + sig.charAt(idx-1) + ", idx=" + idx;
					
					if (sig.charAt(idx) == '>')
					{
						//idx += 2;
					}
				}
				else
				{
					int x = 1;
					x = 2;
				}
			}
			
			private void readTypeName()
			{
				char c;
				while (((c = sig.charAt(idx)) != '*') && (c != ';') && (c != '<'))
				{
					System.out.print(c);
					
					++idx;
				}
			}
			
			private void readGenList(int recIdx)
			{
				if (recIdx > 0)
				{
					System.out.print(", ");
				}
				char c = sig.charAt(idx);
				
				if ((c == '+') || (c == '-') || (c == '*'))
				{
					//++idx;
					if (c == '*')
					{
						System.out.print("any");
					}
				}

				
				readRefType2();
			
				c = sig.charAt(idx);
				
				if (c != '>')
				{
					readGenList(recIdx + 1);
					
					
				}
				else
				{
					idx += 2;
				}
			}
		}
	}

    /**
     * The main method accepts the file(s) for verification. Multiple files can
     * be verified by passing the absolute path of the files as comma-separated
     * argument. This method invokes the custom annotation processor to process
     * the annotations in the supplied file(s). Verification results will be
     * printed on to the console.
     * 
     * @param args
     *            The java source files to be verified.
     */
	public static void main(String[] args) {
		
		try
		{
			DtsWriter dtsWriter = new DtsWriter();
			com.telerik.java.api.bcel.Generator g = new com.telerik.java.api.bcel.Generator(dtsWriter);
			g.visit("C:/Work/NativeScript/android-metadata-generator/jars");
		}
		catch (Exception ex)
		{
			ex.printStackTrace();
		}
		if (args.length == 0) return;
		//
		Input input = Input.Reflection;
		
		switch (input)
		{
			case Reflection:
				processUsingReflection(args);
				break;
				
			case SourceCode:
				processUsingSourceCode(args);
				break;

			default:
				throw new RuntimeException("Not supported input:" + input);
		}
	}
	
    private static void processUsingReflection(String[] args) {
		String jdkPath = "C:\\Program Files\\Java\\jdk1.8.0_45";
		String targetXml = "C:\\temp\\AppCompat_21_JavaDoc.xml";
		String[] directories =
		{
			"C:\\Dev\\adt\\sdk\\platforms\\android-21\\android.jar",
			"C:\\Dev\\adt\\sdk\\extras\\android\\support\\v7\\appcompat\\libs\\android-support-v4.jar",
			"C:\\Dev\\adt\\sdk\\extras\\android\\support\\v7\\appcompat\\libs\\android-support-v7-appcompat.jar"
		};
		buildUsingReflection(jdkPath, targetXml, directories);
    }
    
    public static void processUsingSourceCode(String[] args) {
		String jdkPath = "C:\\Program Files\\Java\\jdk1.8.0_31";
		String targetXml = "C:\\Work\\xPlatCore\\Android_XMLAPI_17_JavaDoc.xml";
		String[] directories =
			{ "C:\\Dev\\adt\\sdk\\sources\\android-17\\android",
			  "C:\\Dev\\adt\\sdk\\sources\\android-17\\java",
			  "C:\\Dev\\adt\\sdk\\sources\\android-17\\javax",
			  "C:\\Dev\\adt\\sdk\\sources\\android-17\\org"
			};
		buildUsingSourceCode(jdkPath, targetXml, directories);
    }
    
	private static void buildUsingSourceCode(String javaHome, String targetXml, String[] directories) {
		System.out.println("Collecting class files...");
    	String xml = targetXml;
    	
        try {
            CodeAnalyzerController controller = new CodeAnalyzerController();
            List<File> files = new ArrayList<File>();
            for (String dir: directories) {
            	addFromFolder(files, dir);	
            }

            System.out.println("Java compiler processing classes to Java AST...");
			controller.invokeProcessor(files);

        } catch (Throwable t) {
            t.printStackTrace();
        }
        
        System.out.println("Converting to TypeScript AST...");
            
        Map<String, JClass> map = ClassModelMap.getInstance().getClassInfoMap();
        Map<String, JClass> rootMap = ClassModelMap.getInstance().getRootClassInfoMap();

    	try {

        	JContext root = new ContextInfo(map, rootMap); // JavaJarContext.fromFile(jar);
        	
			JToJSConverter converter = new JToJSConverter();
			converter.setJContext(root);
			converter.convert();
			JSGlobal global = converter.getJSGlobal();
			
			System.out.println("Printing to XML...");
			
			StringWriter stringWriter = new StringWriter();
			JSToXMLPrinter printer = new JSToXMLPrinter();
			printer.setStringWriter(stringWriter);
			printer.setGlobal(global);
			printer.print();
			
	        Transformer transformer = TransformerFactory.newInstance().newTransformer();
	        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			String stringXml = stringWriter.toString();
			transformer.transform(new StreamSource(new StringReader(stringXml)), new StreamResult(new FileWriter(xml)));

    	} catch(Throwable t2) {
    		t2.printStackTrace();
    	}

        System.out.println("End.");
	}


	private static void buildUsingReflection(String javaHome, String targetXml, String[] directories) {
    	String xml = targetXml;

    	try {

    		JContext root = JavaJarContext.fromFile(directories);
        	
			JToJSConverter converter = new JToJSConverter();
			converter.setJContext(root);
			converter.convert();
			JSGlobal global = converter.getJSGlobal();
			
			System.out.println("Printing to XML...");
			
			StringWriter stringWriter = new StringWriter();
			JSToXMLPrinter printer = new JSToXMLPrinter();
			printer.setStringWriter(stringWriter);
			printer.setGlobal(global);
			printer.print();
			
	        Transformer transformer = TransformerFactory.newInstance().newTransformer();
	        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
			transformer.setOutputProperty(OutputKeys.INDENT, "yes");
			transformer.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
			String stringXml = stringWriter.toString();
			transformer.transform(new StreamSource(new StringReader(stringXml)), new StreamResult(new FileWriter(xml)));

    	} catch(Throwable t2) {
    		t2.printStackTrace();
    	}

        System.out.println("End.");
	}
    
	private static void addFromFolder(List<File> files, String string) {
		files.addAll(listFilesRecursively(new File(string)));
	}

	private static List<File> listFilesRecursively(File parentDir) {
	    ArrayList<File> inFiles = new ArrayList<File>();
	    File[] files = parentDir.listFiles();
	    for (File file : files) {
	        if (file.isDirectory()) {
	            inFiles.addAll(listFilesRecursively(file));
	        } else {
	        	String filePath = file.getAbsolutePath();
	        	if (filePath.contains("\\tests\\"))
	        		continue;
	            if(file.getName().endsWith(".java")){
	                inFiles.add(file);
	            }
	        }
	    }
	    return inFiles;
	}
}
