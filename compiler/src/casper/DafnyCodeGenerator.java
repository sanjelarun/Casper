package casper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import casper.SketchParser.KvPair;
import casper.ast.JavaExt;
import casper.extension.MyWhileExt;
import casper.types.ArrayAccessNode;
import casper.types.ConstantNode;
import casper.types.CustomASTNode;
import casper.types.IdentifierNode;
import casper.types.MRStage;
import casper.types.Variable;
import casper.visit.GenerateScaffold.SearchConfiguration;
import polyglot.ast.Node;

public class DafnyCodeGenerator {
	
	public static void generateSummary(int id, Node n, Set<Variable> outputVars, String reducerType, String sketchReduceType, SearchConfiguration conf) throws IOException {
		// Get node extension
		MyWhileExt ext = (MyWhileExt) JavaExt.ext(n);
		
		/************** Generate Utils File ****************/
		PrintWriter writer = new PrintWriter("output/utils.dfy", "UTF-8");
		String text = new String(Files.readAllBytes(Paths.get("templates/utils.dfy")), StandardCharsets.UTF_8);
		
		// Generate setter functions
		String setterFuncs = generateSetterFunctions(outputVars);
		
		// Generate getter functions
		String getterFuncs = generateGetterFunctions(outputVars, ext.inputVars);
		
		// Generate ite functions
		String iteFuncs = generateITEFunctions(outputVars, ext.inputVars);
		
		text = text.replace("<setter-functions>", setterFuncs);
		text = text.replace("<getter-functions>", getterFuncs);
		text = text.replace("<ite-functions>",iteFuncs);
		
		writer.print(text);
		writer.close();
		
		/************** Create summary verification file ****************/
		
		// Load  template
		String template = new String(Files.readAllBytes(Paths.get("templates/dafny_skeleton.dfy")), StandardCharsets.UTF_8);
		writer = new PrintWriter("output/main_"+reducerType+"_"+id+".dfy", "UTF-8");
		
		// Generate main harness args
		String harnessArgs = generateDafnyHarnessArgs(ext,ext.inputVars,outputVars,ext.loopCounters);
	
		// Generate require statements for dafny main function
		String mainReqStmts = generateRequireStatements(ext,outputVars,ext.wpcs,ext.inputDataSet);
		
		// Init variables in main
		String initVars = generateVarInit(ext,ext.inputVars,outputVars,ext.loopCounters);
		
		// Generate verification code
		String preC = generatePreCondition(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		String loopCond = "("+ext.mainLoopCounter.varName+"<|"+ext.inputDataSet.varName+"|)";
		
		String loopCondFalse = loopCond; loopCondFalse = "!" + loopCondFalse;
		
		String invariant = generateInvariant(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		String wpc = generateWPC(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		String postC = generatePostCondStmt(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		// 1. Assert loop invariant is true before the loop executes.
		String verifCode = "assert " + preC + ";\n\n\t";
		
		// 2. Assert loop invariant is preserved if the loop continues: I && loop condition is true --> wp(c, I), 
		//    where wp(c, I) is the weakest precondition of the body of the loop with I as the post-condition
		//verifCode += "if(" + invariant + " && " + loopCond + ")\n\t{\n\t\t" + lemma + ";\n\t\t"+wpcInits+"assert " + wpc + ";\n\t}\n\n\t";
		verifCode += "if(" + invariant + " && " + loopCond + ")\n\t{\n\t\tassert " + wpc + ";\n\t}\n\n\t";
		
		// 2. Assert loop invariant implies the post condition if the loop terminates: I && loop condition is false --> POST
		verifCode += "if(" + invariant + " && " + loopCondFalse + ")\n\t{\n\t\tassert " + postC + ";\n\t}";
		
		// Generate args for invariant and post condition
		String invPcArgs = generateInvPcAargs(ext,ext.postConditionArgsOrder.get(reducerType),outputVars,ext.loopCounters,ext.inputVars);
		
		// Generate invariant
		String loopInv = generateLoopInv(ext, reducerType, outputVars, ext.loopCounters, ext.inputVars);
		
		// Generate post condition
		String postCond = generatePostCond(ext, reducerType, outputVars, ext.loopCounters, ext.inputVars);
		
		// Generate mapper functions
		String mapperFuncs = generateMapperFunctions(ext);
		
		// Generate mapper functions
		String doMapFuncs = generateDoMapFunctions(ext);
		
		// Generate mapper function args declaration
		String mapperArgsDecl = generateMapperArgsDecl(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Generate mapper function args call
		String mapperArgsCall = generateMapperArgsCall(ext, ext.loopCounters, ext.inputVars, outputVars);
		String mapperArgsCallInd = generateMapperArgsCallInd(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Generate map emits
		String domapEmits = generateMapEmits(ext.solution, mapperArgsCall);
		String emitFuncs = generateEmitFuncs(ext.solution, reducerType, conf);
		
		// Generate terminate condition for map recursion
		//String tCond = generateMapTerminateCondition(ext.loopCounters);
		
		// Generate reduce functions
		String doReduceFunctions = generateDoReduceFunctions(ext, outputVars, conf);
		
		// Generate reduce functions
		String reduceFunctions = generateReduceFunctions(ext, outputVars, reducerType, conf);
		
		// Do reduce args declaration
		//String reducerArgsDecl = generatedReducerArgsDecl(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Do reduce args call
		//String reducerArgsCall = generatedReducerArgsCall(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Generate do reduce key type
		//String doreduceKeyType = generateDoreduceKeyType(ext.mapEmits, conf.keyType);
		
		// Generate dooreduce key require statements
		//String keyRequires = generateKeyRequires(outputVars);
		
		// Generate invariant pre-condition
		String invRequires = generateInvPreCond(ext, outputVars);
		
		// Generate invariant pre-condition
		String emitRequires = generateEmitsPreCond(ext, ext.loopCounters);
		
		// Generate invariant pre-condition
		String mapperRequires = generateMapperPreCond(ext, ext.loopCounters);
		
		// Generate CSG Lemmas
		//String csgLemmas = generateReduceLemmas(ext.mapEmits,ext.reduceExps, conf.valuesTupleSize, outputVars, reducerType);
		
		// Generate User Defined Data Types
		String UDTs = generateUDTs(ext);
		
		// Plug in generated code into template
		template = template.replace("<harness-args>", harnessArgs);
		template = template.replace("<main-requires>", mainReqStmts);
		template = template.replace("<init-vars>", initVars);
		template = template.replace("<verif-code>", verifCode);
		template = template.replace("<inv-pc-args>", invPcArgs);
		template = template.replace("<loop-inv>", loopInv);
		template = template.replace("<post-cond>", postCond);
		template = template.replace("<mapper-functions>", mapperFuncs);
		template = template.replace("<do-map-functions>", doMapFuncs);
		template = template.replace("<domap-emits>", domapEmits);
		template = template.replace("<emit-funcs>", emitFuncs);
		template = template.replace("<mapper-args-decl>", mapperArgsDecl);
		template = template.replace("<mapper-args-call>", mapperArgsCall);
		template = template.replace("<mapper-args-call-inductive>", mapperArgsCallInd);	
		//template = template.replace("<terminate-condition>", tCond);
		template = template.replace("<do-reduce-functions>", doReduceFunctions);
		template = template.replace("<reduce-functions>",reduceFunctions);
		//template = template.replace("<reducer-args-decl>", reducerArgsDecl);
		//template = template.replace("<reducer-args-call>", reducerArgsCall);
		//template = template.replace("<doreduce-key-type>", doreduceKeyType);
		template = template.replace("<output-type>", casper.Util.getDafnyTypeFromRaw(reducerType));
		template = template.replace("<inv-requires>", invRequires);
		//template = template.replace("<emit-requires>", emitRequires);
		//template = template.replace("<mapper-requires>", mapperRequires);
		template = template.replace("<udts>", UDTs);
		//template = template.replace("<key-requires>",keyRequires);
		
		writer.print(template);
		writer.close();
		
/*		// Generate CFG Proof
		template = new String(Files.readAllBytes(Paths.get("templates/dafny_skeleton2.dfy")), StandardCharsets.UTF_8);
		writer = new PrintWriter("output/main_"+reducerType+"_"+id+"_CSG.dfy", "UTF-8");
		
		// Plug in generated code into template
		template = template.replace("<reduce-exp-lemma>", csgLemmas);
		template = template.replace("<domap-emit-type>", domapEmitType);
		template = template.replace("<reduce-exp>",reduceExp);
		template = template.replace("<reduce-functions>",reduceFunctions);
		template = template.replace("<reducer-args-decl>", reducerArgsDecl);
		template = template.replace("<reducer-args-call>", reducerArgsCall);
		template = template.replace("<doreduce-key-type>", doreduceKeyType);
		template = template.replace("<output-type>", casper.Util.getDafnyTypeFromRaw(reducerType));
		template = template.replace("<reduce-init-value>", reduceInitValues);
		template = template.replace("<udts>", UDTs);
		template = template.replace("<key-requires>",keyRequires);
		
		writer.print(template);
		writer.close();*/
	}

	private static String generateDoReduceFunctions(MyWhileExt ext, Set<Variable> outputVars, SearchConfiguration conf) {
		String code = "";
		int index = 0;
		for(MRStage stage : ext.solution) {
			if(stage.stageType == 1) {
				// Get lc name
				String lcName = getLcName(ext.loopCounters);
				
				// Basic inputs
				String inputs = ext.inputDataSet.varName+": "+ext.inputDataSet.getDafnyType()+", "+lcName+": int";
				
				// Generate args
				String args = getArgsDecl(ext,ext.inputVars,outputVars,ext.loopCounters);
				
				// Generate redue exp
				String reduceExp = generateReduceExp(ext, outputVars, conf, index);
				
				// Generate reduce init values
				String reduceInitValues = generateReduceInitValues(stage.initExps, outputVars);
				
				code += "function doreduce_"+index+"("+inputs+args+") : <output-type>\n" + 
						"    requires 0 <= " + lcName + " <= |" + ext.inputDataSet.varName + "|\n" +
						"{\n" + 
						"    if "+lcName+" == 0 then "+reduceInitValues+"\n" + 
						"    else ("+reduceExp+")\n" + 
						"}";
			}
			/*if(stage.stageType == 1) {
				code += "function doreduce_"+index+"(input: <domap-emit-type>, casper_key: <doreduce-key-type><reducer-args-decl>) : <output-type>\n" + 
						"    <key-requires>\n" + 
						"    ensures (|input| > 0 && input[0].0 == casper_key) ==> \n" + 
						"        doreduce_"+index+"(input, casper_key<reducer-args-call>) == (<reduce-exp>)\n" + 
						"    ensures (|input| > 0 && input[0].0 != casper_key) ==> \n" + 
						"        doreduce_"+index+"(input, casper_key<reducer-args-call>) == doreduce_"+index+"(input[1..], casper_key<reducer-args-call>)\n" + 
						"{\n" + 
						"    if input == [] then (<reduce-init-value> )\n" + 
						"    else if input[0].0 == casper_key then (<reduce-exp>)\n" + 
						"    else doreduce_"+index+"(input[1..], casper_key<reducer-args-call>)\n" + 
						"}";
			}*/
			index++;
		}
		return code;
	}
	
	private static String generateDoMapFunctions(MyWhileExt ext) {
		String code = "";
		int index = 0;
		for(MRStage stage : ext.solution) {
			if(stage.stageType == 0) {
				code += "function domap_"+index+" (<mapper-args-decl>) : <domap-emit-type>\n" + 
						"    <emit-requires>\n" + 
						"    ensures domap_"+index+"(<mapper-args-call>) == <domap-emits>\n" + 
						"{\n" + 
						"    <domap-emits>\n" + 
						"}";
			}
			index++;
		}
		return code;
	}

	private static String generateMapperFunctions(MyWhileExt ext) {
		String code = "";
		int index = 0;
		for(MRStage stage : ext.solution) {
			if(stage.stageType == 0) {
				code += "function mapper_"+index+" (<mapper-args-decl>) : <domap-emit-type>\n" + 
						"    <mapper-requires>\n" + 
						"{\n" + 
						"    if <terminate-condition> then []\n" + 
						"    else domap_"+index+"(<mapper-args-call-inductive>) + mapper_"+index+"(<mapper-args-call-inductive>)\n" + 
						"}";
			}
			index++;
		}
		return code;
	}

	private static String generateSetterFunctions(Set<Variable> outputVars) {
		String code = "";
		Set<String> handledTypes = new HashSet<String>();
		for(Variable v : outputVars) {
			if( casper.Util.getTypeClass(v.getSketchType()) == casper.Util.ARRAY ||
				casper.Util.getTypeClass(v.getSketchType()) == casper.Util.OBJECT_ARRAY )
			{	
				if(handledTypes.contains(v.getSketchArrayType()))
					continue;
				else 
					handledTypes.add(v.getSketchArrayType());
				
				code += "function " + v.getSketchArrayType()+"_setter(arrayvar: "+v.getDafnyType()+", index: int, value: "+v.getDafnyArrayType()+" value) : " + v.getDafnyType() + " {\n" + 
						"	arrayvar[index := value]\n" +  
						"}\n\n";
			}
		}
		
		if(code.length() > 0) code = code.substring(0,code.length()-2);
		return code;
	}

	private static String generateGetterFunctions(Set<Variable> outputVars, Set<Variable> inputVars) {
		String code = "";
		Set<String> handledTypes = new HashSet<String>();
		for(Variable v : outputVars) {
			if( casper.Util.getTypeClass(v.getSketchType()) == casper.Util.ARRAY ||
				casper.Util.getTypeClass(v.getSketchType()) == casper.Util.OBJECT_ARRAY )
			{	
				if(handledTypes.contains(v.getSketchArrayType()))
					continue;
				else 
					handledTypes.add(v.getSketchArrayType());
				
				code += "function " + v.getSketchArrayType()+"_getter(arrayvar: "+v.getDafnyType()+", index: int) : " + v.getDafnyArrayType() + " {\n" + 
						"	arrayvar[index]\n" +  
						"}\n\n";
			}
		}
		
		for(Variable v : inputVars) {
			if( casper.Util.getTypeClass(v.getSketchType()) == casper.Util.ARRAY ||
				casper.Util.getTypeClass(v.getSketchType()) == casper.Util.OBJECT_ARRAY )
			{	
				if(handledTypes.contains(v.getSketchArrayType()))
					continue;
				else 
					handledTypes.add(v.getSketchArrayType());
				
				code += "function " + v.getSketchArrayType()+"_getter(arrayvar: "+v.getDafnyType()+", index: int) : " + v.getDafnyArrayType() + " {\n" + 
						"	arrayvar[index]\n" +  
						"}\n\n";
			}
		}
		
		if(code.length() > 0) code = code.substring(0,code.length()-2);
		return code;
	}

	private static String generateITEFunctions(Set<Variable> outputVars, Set<Variable> inputVars) {
		String code = "";
		Set<String> handledTypes = new HashSet<String>();
		for(Variable v : outputVars) {
			if( casper.Util.getTypeClass(v.getSketchType()) == casper.Util.ARRAY ||
				casper.Util.getTypeClass(v.getSketchType()) == casper.Util.OBJECT_ARRAY )
			{
				if(!handledTypes.contains(v.getSketchArrayType()))
				{
					handledTypes.add(v.getSketchArrayType());
				
					code += "function " + v.getSketchArrayType()+"_ite(condition: bool, op1: "+v.getDafnyArrayType()+", op2: " + v.getDafnyArrayType() + ") : " + v.getDafnyArrayType() + " {\n" + 
							"	if condition then op1 else op2\n" +  
							"}\n\n";
				}
				
				if(!handledTypes.contains(v.getSketchType()))
				{
					handledTypes.add(v.getSketchType());
					
					code += "function " + v.getSketchArrayType()+"array_ite(condition: bool, op1: "+v.getDafnyType()+", op2: " + v.getDafnyType() + ") : " + v.getDafnyType() + " {\n" + 
							"	if condition then op1 else op2\n" +  
							"}\n\n";
				}
			}
		}
		
		for(Variable v : inputVars) {
			if( casper.Util.getTypeClass(v.getSketchType()) == casper.Util.ARRAY ||
				casper.Util.getTypeClass(v.getSketchType()) == casper.Util.OBJECT_ARRAY )
			{
				if(!handledTypes.contains(v.getSketchArrayType()))
				{
					handledTypes.add(v.getSketchArrayType());
				
					code += "function " + v.getSketchArrayType()+"_ite(condition: bool, op1: "+v.getDafnyArrayType()+", op2: " + v.getDafnyArrayType() + ") : " + v.getDafnyArrayType() + " {\n" + 
							"	if condition then op1 else op2\n" +  
							"}\n\n";
				}
				
				if(!handledTypes.contains(v.getSketchType()))
				{
					handledTypes.add(v.getSketchType());
					
					code += "function " + v.getSketchArrayType()+"array_ite(condition: bool, op1: "+v.getDafnyType()+", op2: " + v.getDafnyType() + ") : " + v.getDafnyType() + " {\n" + 
							"	if condition then op1 else op2\n" +  
							"}\n\n";
				}
			}
		}
		
		if(code.length() > 0) code = code.substring(0,code.length()-2);
		return code;
	}

	private static String generateKeyRequires(Set<Variable> outputVars) {
		String code = "";
		int index = 1;
		String app = "requires ";
		for(Variable var : outputVars){
			if(var.getSketchType().endsWith("["+Configuration.arraySizeBound+"]")){
				code += "requires casper_key.0 == "+index+" ==> 0 <= casper_key.1 < |"+var.varName+"0|\n\t";
			}
			else{
				app += "casper_key == "+index+" || ";
			}
			index++;
		}
		if(code.equals(""))
			return app.substring(0, app.length()-4);
		else
		return code;
	}

	private static String generateReduceFunctions(MyWhileExt ext, Set<Variable> outputVars, String reducerType, SearchConfiguration conf) {
		String code = "";
		int index = 0;
		for(MRStage stage : ext.solution) {
			if(stage.stageType == 1) {
				for(Variable var : outputVars){
					String reduceExp = stage.reduceExps.get(var.varName);
					reduceExp = stage.reduceExps.get(var.varName);
					reduceExp = reduceExp.replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false");
					
					String type = casper.Util.getDafnyTypeFromRaw(reducerType);
					
					String args = "";
					for(int i=3; i<conf.valuesTupleSize+3 && conf.tuplesAdded; i++)
						args += ", val"+ i + ": "+type;
					
					code += "function reduce_"+index+"_"+var.varName+"(val1: "+type+", val2: "+type+args+getArgsDecl(ext,ext.inputVars,outputVars,ext.loopCounters)+") : " + type + "\n";
					code += "{" + "\n";
					code += "\t"+reduceExp + "\n";
					code += "}" + "\n";
				}
			}
		}
		return code;
	}

	private static String generateUDTs(MyWhileExt ext) {
		String code = "";
		for(String type : ext.globalDataTypes){
			code += "class " + type + "{\n\t";
			for(Variable field : ext.globalDataTypesFields.get(type)){
				code += "var " + field.varName + ": " + field.getDafnyType() + ";\n\t";
			}
			code = code.substring(0,code.length()-1) + "}\n";
		}
		return code;
	}

	public static String generateDafnyHarnessArgs(MyWhileExt ext, Set<Variable> inputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String args = "";
	
		args += ext.inputDataSet.varName + ": " + ext.inputDataSet.getDafnyType();
		
		for(Variable var : outputVars){
			args += ", " + var.varName + ": " + var.getDafnyType();
			if(!ext.initVals.containsKey(var.varName) || (ext.initVals.get(var.varName) instanceof ConstantNode && ((ConstantNode)ext.initVals.get(var.varName)).type_code == ConstantNode.ARRAYLIT)){
				args += ", " + var.varName + "0: " + var.getDafnyType();
			}
		}
		for(Variable var : inputVars){
			if(ext.inputDataCollections.contains(var) || ext.inputDataSet.equals(var))
				continue;
			
			if(!ext.initVals.containsKey(var.varName) || (ext.initVals.get(var.varName) instanceof ConstantNode && ((ConstantNode)ext.initVals.get(var.varName)).type_code == ConstantNode.STRINGLIT)){
				args += ", " + var.varName + ": " + var.getDafnyType();
			}
		}
		for(Variable var : loopCounters){
			args += ", " + var.varName + ": " + var.getDafnyType();
			if(!ext.initVals.containsKey(var.varName)){
				args += ", " + var.varName + "0: " + var.getDafnyType();
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i + ": int";
		}
			
		return args;
	}
	
	public static String generateRequireStatements(MyWhileExt ext, Set<Variable> outputVars, Map<String, CustomASTNode> wpcValues, Variable inputDataSet) {
		String code = "";
		
		if(!ext.initInpCollection){
			code += "requires " + ext.inputDataSet.varName + " == " + ext.inputDataCollections.get(0).varName +";\n\t";
		}
		
		if(casper.Util.getDafnyTypeClass(inputDataSet.getDafnyType()) == casper.Util.OBJECT_ARRAY){
			code += "requires null !in " +  inputDataSet.varName + "\n\t";
		}
		
		for(Variable var : outputVars){
			if(casper.Util.getDafnyTypeClass(var.getDafnyType()) == casper.Util.ARRAY || casper.Util.getDafnyTypeClass(var.getDafnyType()) == casper.Util.OBJECT_ARRAY){
				code += "requires  |" + var.varName + "| == |" + var.varName + "0|\n\t";
				if(ext.initVals.containsKey(var.varName) && ext.initVals.get(var.varName) instanceof ConstantNode && ((ConstantNode)ext.initVals.get(var.varName)).type_code == ConstantNode.ARRAYLIT){
					code += "requires  forall k :: 0 <= k < |" + var.varName + "0| ==> " + var.varName + "0[k] == "+ ext.initVals.get(var.varName) +"\n\t";
				}
			}
		}
		
		Map<String,List<CustomASTNode>> indexes = new HashMap<String,List<CustomASTNode>>();
		for(Variable var : outputVars){
			if(wpcValues.containsKey(var.varName)){
				CustomASTNode wpcValue = wpcValues.get(var.varName);
				if(var.category == Variable.ARRAY_ACCESS){
					// Indexes requirement
					indexes.put(var.varName, new ArrayList<CustomASTNode>());
					wpcValue.getIndexes(var.varName,indexes);
					for(CustomASTNode index : indexes.get(var.varName)){
						if(index instanceof ArrayAccessNode){
							code += "requires forall k :: 0 <= k < |" + ((ArrayAccessNode)index).array + "| ==> 0 <= " + ((ArrayAccessNode)index).array + "[k] < |" + var.varName + "|\n\t";
						}
						//else if(index instanceof FieldNode){
						//	code += "requires forall k :: 0 <= k < |" + ((ArrayAccessNode)((FieldNode)index).container).array + "| ==> 0 <= " + ((ArrayAccessNode)((FieldNode)index).container).array + "[k]."+index.toString().substring(index.toString().lastIndexOf(".")+1, index.toString().length())+" < |" + var.varName + "|\n\t";
						//}
						else{
							code += "requires 0 <= "+ext.mainLoopCounter.varName+" < |"+ext.inputDataSet.varName+"| ==> 0 <= " + index + " < |" + var.varName + "|\n\t";
						}
					}
				}
			}
		}
		
		if(code.length()>0) code = code.substring(0,code.length()-2);
		return code;
	}
	
	public static String generateVarInit(MyWhileExt ext, Set<Variable> inputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String code = "";
		
		for(Variable var : outputVars){
			if(ext.initVals.containsKey(var.varName) && var.category != Variable.ARRAY_ACCESS){
				code += "var " + var.varName + "0 := " + ext.initVals.get(var.varName) + ";\n\t";
			}
		}
		for(Variable var : inputVars){
			if(ext.inputDataCollections.contains(var) || ext.inputDataSet.equals(var))
				continue;
			
			if(ext.initVals.containsKey(var.varName)){
				if(!(ext.initVals.get(var.varName) instanceof ConstantNode && ((ConstantNode) ext.initVals.get(var.varName)).type_code == ConstantNode.STRINGLIT))
					code += "var " + var.varName + " := " + ext.initVals.get(var.varName) + ";\n\t";
			}
		}
		for(Variable var : loopCounters){
			if(ext.initVals.containsKey(var.varName)){
				code += "var " + var.varName + "0 := " + ext.initVals.get(var.varName) + ";\n\t";
			}
		}
		
		return code;
	}
	
	public static String generatePreCondition(MyWhileExt ext, String type, Set<Variable> inputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String code = "";
		
		code = ext.preConditions.get(type).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName,ext.inputDataSet.getSketchType())).toString();
		code = code.substring(0,code.length()-1) + ",";
		for(Variable v : inputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v) && !ext.inputDataSet.equals(v)){
				code += v.varName + ",";
			}
		}
		for(int i=0; i<ext.constCount; i++){
			code += "casperConst" + i + ",";
		}
		code = code.substring(0,code.length()-1) + ")";
		
		return code;
	}
	
	public static String generateInvariant(MyWhileExt ext, String type, Set<Variable> InputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String invariant = "";
		
		invariant = ext.invariants.get(type).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName,ext.inputDataSet.getSketchType())).toString();
		invariant = invariant.substring(0,invariant.length()-1) + ",";
		for(Variable v : InputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v) && !ext.inputDataSet.equals(v)){
				invariant += v.varName + ",";
			}
		}
		for(int i=0; i<ext.constCount; i++){
			invariant += "casperConst" + i + ",";
		}
		invariant = invariant.substring(0,invariant.length()-1) + ")";
		
		return invariant;
	}

	public static String generateWPC(MyWhileExt ext, String outputType, Set<Variable> InputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String wpc = "";
		
		wpc = ext.wpcs.get(outputType).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName,ext.inputDataSet.getSketchType())).toString();
		wpc = wpc.substring(0,wpc.length()-1) + ",";
		for(Variable v : InputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v) && !ext.inputDataSet.equals(v)){
				wpc += v.varName + ",";
			}
		}
		for(int i=0; i<ext.constCount; i++){
			wpc += "casperConst" + i + ",";
		}
		wpc = wpc.substring(0,wpc.length()-1) + ")";
		
		return wpc;
	}

	public static String generatePostCondStmt(MyWhileExt ext, String outputType, Set<Variable> InputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String pcond = "";
		
		pcond = ext.postConditions.get(outputType).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName,ext.inputDataSet.getSketchType())).toString();
		pcond = pcond.substring(0,pcond.length()-1) + ",";
		for(Variable v : InputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v) && !ext.inputDataSet.equals(v)){
				pcond += v.varName + ",";
			}
		}
		for(int i=0; i<ext.constCount; i++){
			pcond += "casperConst" + i + ",";
		}
		pcond = pcond.substring(0,pcond.length()-1) + ")";
		
		return pcond;
	}
	
	public static String generateWPCInits(Map<String, CustomASTNode> wpcValues, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String code = "";
		/*for(String varname : wpcValues.keySet())
		{
			boolean found = false;
			for(Variable var : outputVars){
				if(var.varName.equals(varname)){
					code += "var ind_" + varname + " := " + varname + ";\n\t\t";
					CustomASTNode value = wpcValues.get(varname);
					if(value instanceof ConditionalNode){
						code += ((ConditionalNode)value).toStringDafny("ind_" + varname + " := ");
					}
					else if(value instanceof ArrayUpdateNode){
						code += "ind_" + varname + " := " + ((ArrayUpdateNode)value).toStringDafny() + ";\n\t\t";
					}
					else if(value instanceof SequenceNode){
						code += ((SequenceNode) value).inst1ToStringDafny("ind_" + varname + " := ") + ";\n\t\t" + ((SequenceNode) value).inst2ToStringDafny("ind_" + varname + " := ") + ";\n\t\t";
					}
					else{
						code += "ind_" + varname + " := " + value + ";\n\t\t";
					}
					found = true;
					break;
				}
			}
			if(found) continue;
			for(Variable var : loopCounters){
				if(var.varName.equals(varname)){
					code += "var ind_" + varname + " := " + varname + ";\n\t\t";
					CustomASTNode value = wpcValues.get(varname);
					if(value instanceof ConditionalNode){
						code += ((ConditionalNode)value).toString("ind_" + varname + " := ");
					}
					else if(value instanceof ArrayUpdateNode){
						code += "ind_" + varname + " := " + ((ArrayUpdateNode)value).toStringDafny() + ";\n\t\t";
					}
					else{
						code += "ind_" + varname + " := " + value + ";\n\t\t";
					}
					break;
				}
			}
		}*/
		return code;
	}
	
	public static String generateInvPcAargs(MyWhileExt ext, List<String> loopInvariantArgsOrder, Set<Variable> outputVars, Set<Variable> loopCounters, Set<Variable> InputVars) {
		String code = "";
		
		code += ext.inputDataSet.varName + ": " + ext.inputDataSet.getDafnyType();
		
		for(String nextVarName : loopInvariantArgsOrder){
			for(Variable var : outputVars){
				if(nextVarName.equals(var.varName)){
					code += ", " + var.varName + ": " + var.getDafnyType();
					code += ", " + var.varName + "0: " + var.getDafnyType();
				}
			}
			for(Variable var : loopCounters){
				if(nextVarName.equals(var.varName)){
					code += ", " + var.varName + ": " + var.getDafnyType();
					code += ", " + var.varName + "0: " + var.getDafnyType();
				}
			}
		}
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				code += ", " + var.varName + ": " + var.getDafnyType();
			}
		}
		for(int i=0; i<ext.constCount; i++){
			code += ", " + "casperConst" + i + ": int";
		}
		
		return code;
	}
	
	public static String getLcName(Set<Variable> loopCounters) {
		for(Variable lc : loopCounters){
			return lc.varName;
		}
		return null;
	}
	
	public static String generateLoopInv(MyWhileExt ext, String type, Set<Variable> outputVars, Set<Variable> loopCounters, Set<Variable> inputVars) {
		String code = "";
		
		// Generate args
		String args = getArgsCall(ext,inputVars,outputVars,loopCounters);
		
		// Generate loop inv body
		code += "0 <= " + getLcName(loopCounters) + " <= |" + ext.inputDataSet.varName + "| &&\n\t";
		int index = 1;
		for(Variable var : outputVars){
			String exp = "";
			if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.PRIMITIVE){ 
				for(int i=ext.solution.size()-1; i>=0; i--) {
					MRStage stage = ext.solution.get(i);
					if(stage.stageType == 0) {
						if(i == ext.solution.size()-1) {
							exp = "mapper_"+i+"("+args+")";
						}
						else {
							exp = "mapper_"+i+"("+exp+")";
						}
					}
					else if (stage.stageType == 1) {
						if(i == ext.solution.size()-1) {
							exp = "doreduce_"+i+"("+ext.inputDataSet.varName+","+getLcName(loopCounters)+args+")";
						}
						else {
							exp = "doreduce_"+i+"("+exp+","+getLcName(loopCounters)+args+")";
						}
						if(i==0) {
							String exp2 = stage.mergeExps.get(var.varName).replace("val1", exp);
							exp2 = exp2.replace("val2", var.varName + "0");
							exp = "("+ var.varName + " == (" + exp2 + ")) &&\n\t";
						}
					}	  
				}
				index++;
			}
			else if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.ARRAY){
				for(int i=ext.solution.size()-1; i>=0; i--) {
					MRStage stage = ext.solution.get(i);
					if(stage.stageType == 0) {
						if(i == ext.solution.size()-1) {
							exp = "mapper_"+i+"("+args+")";
						}
						else {
							exp = "mapper_"+i+"("+exp+")";
						}
						if(i==0) {
							exp = "(forall k :: 0 <= k < |" + var.varName  + "| ==> " + var.varName + "[k] == (" + exp + ")[k])	 &&\n\t";
						}
					}
					else if (stage.stageType == 1) {
						if(i == ext.solution.size()-1) {
							exp = "doreduce_"+i+"("+ext.inputDataSet.varName+","+getLcName(loopCounters)+args+")";
						}
						else {
							exp = "doreduce_"+i+"("+exp+","+getLcName(loopCounters)+args+")";
						}
					}	  
				}
				index++;
				
			}
			code += exp;
		} 
		
		code = code.substring(0,code.length()-5);
		
		return code;
	}
	
	public static String getArgsCall(MyWhileExt ext, Set<Variable> inputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String args = "";
		for(Variable var : inputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ","+var.varName;
			}
		}
		for(Variable var : outputVars){
			args += "," + var.varName+ "0";
		}
		for(int i=0; i<ext.constCount; i++){
			args += "," + "casperConst" + i;
		}
		return args;
	}
	
	public static String getArgsDecl(MyWhileExt ext, Set<Variable> inputVars, Set<Variable> outputVars, Set<Variable> loopCounters) {
		String args = "";
		for(Variable var : inputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", "+var.varName+": "+var.getDafnyType();
			}
		}
		for(Variable var : outputVars){
			args += ", " + var.varName+ "0: " + var.getDafnyType();
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i + ": int";
		}
		return args;
	}
	
	public static String generatePostCond(MyWhileExt ext, String type, Set<Variable> outputVars, Set<Variable> loopCounters, Set<Variable> inputVars) {
		String code = "";
		
		// Generate args
		String args = getArgsCall(ext,inputVars,outputVars,loopCounters);
		
		// Generate loop inv body
		code += getLcName(loopCounters) + " == |" + ext.inputDataSet.varName + "| &&\n\t";
		int index = 1;
		for(Variable var : outputVars){
			String exp = "";
			if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.PRIMITIVE){ 
				for(int i=ext.solution.size()-1; i>=0; i--) {
					MRStage stage = ext.solution.get(i);
					if(stage.stageType == 0) {
						if(i == ext.solution.size()-1) {
							exp = "mapper_"+i+"("+args+")";
						}
						else {
							exp = "mapper_"+i+"("+exp+")";
						}
					}
					else if (stage.stageType == 1) {
						if(i == ext.solution.size()-1) {
							exp = "doreduce_"+i+"("+ext.inputDataSet.varName+","+getLcName(loopCounters)+args+")";
						}
						else {
							exp = "doreduce_"+i+"("+exp+","+getLcName(loopCounters)+args+")";
						}
						if(i==0) {
							String exp2 = stage.mergeExps.get(var.varName).replace("val1", exp);
							exp2 = exp2.replace("val2", var.varName + "0");
							exp = "("+ var.varName + " == (" + exp2 + ")) &&\n\t";
						}
					}	  
				}
				index++;
			}
			else if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.ARRAY){
				for(int i=ext.solution.size()-1; i>=0; i--) {
					MRStage stage = ext.solution.get(i);
					if(stage.stageType == 0) {
						if(i == ext.solution.size()-1) {
							exp = "mapper_"+i+"("+args+")";
						}
						else {
							exp = "mapper_"+i+"("+exp+")";
						}
						if(i==0) {
							exp = "(forall k :: 0 <= k < |" + var.varName  + "| ==> " + var.varName + "[k] == (" + exp + ")[k])	 &&\n\t";
						}
					}
					else if (stage.stageType == 1) {
						if(i == ext.solution.size()-1) {
							exp = "doreduce_"+i+"("+ext.inputDataSet.varName+","+getLcName(loopCounters)+args+")";
						}
						else {
							exp = "doreduce_"+i+"("+exp+","+getLcName(loopCounters)+args+")";
						}
					}	  
				}
				index++;
			}
			code += exp;
		} 
		
		code = code.substring(0,code.length()-5);
		
		return code;
	}
	
	public static String generateMapperArgsDecl(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		args += ext.inputDataSet.varName + ": " + ext.inputDataSet.getDafnyType();
		
		for(Variable var : loopCounters){
			args += ", " + var.varName + "0: " + var.getDafnyType();
			args += ", " + var.varName + ": " + var.getDafnyType();
		}
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", " + var.varName + ": " + var.getDafnyType();
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i + ": int";
		}
		
		return args;
	}
	
	public static String generateMapperArgsCall(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		args += ext.inputDataSet.varName;
		
		for(Variable var : loopCounters){
			args += ", " + var.varName + "0";
			args += ", " + var.varName;
		}
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", " + var.varName;
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i;
		}
		
		return args;
	}
	
	public static String generateMapperArgsCallInd(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		args += ext.inputDataSet.varName;
		
		for(Variable var : loopCounters){
			args += ", " + var.varName + "0";
			args += ", " + var.varName + "-1";
		}
		
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", " + var.varName;
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i;
		}
		
		return args;
	}
	
	public static String generateMapperArgsCallInd2(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		args += ext.inputDataSet.varName;
		
		for(Variable var : loopCounters){
			args += ", " + var.varName + "0";
			args += ", " + var.varName + "+1";
		}
		
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", " + var.varName;
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i;
		}
		
		return args;
	}
	
	public static String generateMapEmits(List<MRStage> solution, String mapperArgsCall) {
		String code = "";
		
		int stageid = 0;
		for(MRStage stage : solution) {
			int index = 0;
			if(stage.stageType == 0) {
				for(String cond : stage.mapEmits.keySet()){
					for(KvPair kvp : stage.mapEmits.get(cond)){
						code += "emit_" + stageid + "_" + index + "(" + mapperArgsCall + ") + ";
						index++;
					}
				}
				code = code.substring(0,code.length()-3);
			}
			stageid++;
		}
		return code;
	}
	
	public static String generateEmitFuncs(List<MRStage> solution, String reducerType, SearchConfiguration conf) {
		String code = "";
		int index = 0;
		
		int stageid = 0;
		for(MRStage stage : solution) {
			if(stage.stageType == 0) {
				String emitType = generateDomapEmitType(stage.mapEmits, reducerType, conf.keyType);
				
				for(String cond : stage.mapEmits.keySet()){
					for(KvPair kvp : stage.mapEmits.get(cond)){
						String emit = "[(";
						if(kvp.keys.size()>1) emit += "(";
						for(Integer i : kvp.keys.keySet()){
							String key = kvp.keys.get(i);
							if(i>1){
								key = key.replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false");
							}
							emit += key + ",";
						}
						if(kvp.keys.size()>1) emit = emit.substring(0,emit.length()-1) + "),";
						
						if(kvp.values.size()>1) emit += "(";
						for(Integer i : kvp.values.keySet()){
							String value = kvp.values.get(i);
							value = value.replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false");
							emit += value + ",";
						}
						emit = emit.substring(0,emit.length()-1);
						if(kvp.values.size()>1) emit += ")";
						emit += ")]";
						
						if(cond.equals("noCondition")){
							code += "function emit_" + stageid + "_" + index + "(<mapper-args-decl>) : <domap-emit-type>\n\t" + 
									"<emit-requires>\n\t" +
									"ensures emit_"+ stageid + "_" + index+"(<mapper-args-call>) == "+emit+"\n" +
								"{\n\t" +
								  emit + "\n" +
								"}\n\n";
						}
						else{
							cond = cond.replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false");
							code += "function emit_" + stageid + "_" + index + "(<mapper-args-decl>) : "+emitType+"\n\t" + 
									"<emit-requires>\n\t" +
									"ensures ("+cond+") ==> (emit_"+stageid+"_"+index+"(<mapper-args-call>) == "+emit+")\n" +
								"{\n\t" +
								  "if "+cond+" then " + emit + "\n\t" +
								  "else []\n" +
								"}\n\n";
						}
						index++;
					}
				}
			}
			stageid++;
		}
		
		return code;
	}
	
	public static String generateMapTerminateCondition(Set<Variable> loopCounters) {
		String code = "";
		for(Variable var : loopCounters){
			code += var.varName + " == 0";
			break;
		}
		return code;
	}
	
	public static String generateDomapEmitType(Map<String, List<KvPair>> mapEmits, String type, String keysType) {
		String code = "";
		// Convert mapKeyType to dafny types
		String keyType = "";
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.keys.size()>1) keyType += "(";
				for(Integer i : kvp.keys.keySet()){
					if(i==0)
						keyType += "int,";
					else
						keyType += casper.Util.getDafnyTypeFromRaw(keysType)+",";
				}
				keyType = keyType.substring(0,keyType.length()-1);
				if(kvp.keys.size()>1) keyType = keyType + ")";
				break;
			}
		}
		
		String valType = "";
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.values.size()>1) valType += "(";
				for(Integer i : kvp.values.keySet()){
					valType += casper.Util.getDafnyTypeFromRaw(type)+",";
				}
				valType = valType.substring(0,valType.length()-1);
				if(kvp.values.size()>1) valType = valType + ")";
				break;
			}
		}
		
		code += "seq<("+keyType+", "+valType+")>";
		return code;
	}
	
	public static String generatedReducerArgsDecl(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", " + var.varName + ": " + var.getDafnyType();
			}
		}
		for(Variable var : outputVars){
			args += ", " + var.varName + "0: " + var.getDafnyType();
		}
		return args;
	}
	
	public static String generatedReducerArgsCall(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var) && !ext.inputDataSet.equals(var)){
				args += ", " + var.varName;
			}
		}
		for(Variable var : outputVars){
			args += ", " + var.varName+ "0";
		}
		
		return args;
	}
	
	public static String generateDoreduceKeyType(Map<String, List<KvPair>> mapEmits, String type) {
		String keyType = "";
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.keys.size()>1) keyType += "(";
				for(Integer i : kvp.keys.keySet()){
					if(i==0)
						keyType += "int,";
					else
						keyType += casper.Util.getDafnyTypeFromRaw(type)+",";
				}
				keyType = keyType.substring(0,keyType.length()-1);
				if(kvp.keys.size()>1) keyType = keyType + ")";
				break;
			}
		}
		return keyType;
	}
	
	private static String generateReduceInitValues(Map<String, String> initExps, Set<Variable> outputVars) {
		for(Variable var : outputVars){
			if(var.getSketchType().endsWith("["+Configuration.arraySizeBound+"]")){
				return initExps.get(var.varName).replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false").replace(var.varName+"0", var.varName+"0[casper_key.1]");
			}
			else{
				return initExps.get(var.varName).replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false");
			}
		}
		return "";
		
		/*String code = "";
		int index = 1;
		String val = "";
		for(Variable var : outputVars){
			if(var.getSketchType().endsWith("["+Configuration.arraySizeBound+"]")){
				val = initExps.get(var.varName).replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false").replace(var.varName+"0", var.varName+"0[casper_key.1]");
				code += "if casper_key.0 == " + index + " then " + val + " else "; 
				index++;
			}
			else{
				val = initExps.get(var.varName).replaceAll("CASPER_TRUE", "true").replaceAll("CASPER_FALSE", "false");
				code += "if casper_key == " + index + " then " + val + " else "; 
				index++;
			}
		}
		code = code + val;
		return code;*/
	}
	
	public static String generateReduceExp(MyWhileExt ext, Set<Variable> outputVars, SearchConfiguration conf, int index) {
		String reduceExp = "";
		
		String lcName = getLcName(ext.loopCounters);
		String argsCall = getArgsCall(ext,ext.inputVars,outputVars,ext.loopCounters);
		
		for(Variable var : outputVars){
			String args = "";
			if(conf.valuesTupleSize == 1 || !conf.tuplesAdded){
				args = ext.inputDataSet.varName+"["+lcName+"-1]";
			}
			else{
				for(int i=1; i<conf.valuesTupleSize*2; i++)
					args += ext.inputDataSet.varName+"["+lcName+"-1],";
				args=args.substring(0,args.length()-1);
			}
			reduceExp = "reduce_"+index+"_" + var.varName + "(doreduce_"+index+"("+ext.inputDataSet.varName+","+lcName+"-1"+argsCall+"),"+args+argsCall+")"; 
		}
		return reduceExp;
	}
	
	/*public static String generateReduceExp(Map<String, List<KvPair>> mapEmits, int valCount, Set<Variable> outputVars) { 
		boolean keyIsTuple = false;
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.keys.size()>1) keyIsTuple = true;
			}
		}
		
		String key = "casper_key";
		if(keyIsTuple) key = "casper_key.0";//<reducer-args-call>
		
		String code = "";
		int index = 1;
		String reduceExp = "";
		for(Variable var : outputVars){
			String args = "";
			if(valCount == 1){
				args = "input[0].1";
			}
			else{
				for(int i=0; i<valCount; i++)
					args += "input[0].1."+i+",";
			}
			reduceExp = "reduce_" + var.varName + "(doreduce(input[1..], casper_key<reducer-args-call>),"+args+"<reducer-args-call>)";
			code += "if " + key + " == " + index + " then "+reduceExp+" else "; 
			index++;
		}
		code = code + "("+ reduceExp + ")";
		return code;
	}*/
	
	public static String generateInvPreCond(MyWhileExt ext, Set<Variable> outputVars) {
		String code = "";
		if(casper.Util.getDafnyTypeClass(ext.inputDataSet.getDafnyType()) == casper.Util.OBJECT_ARRAY){
			code += "requires null !in " +  ext.inputDataSet.varName + "\n\t";
			code += "reads " + ext.inputDataSet.varName + "\n\t";
		}
		for(Variable var : outputVars){
			if(casper.Util.getDafnyTypeClass(var.getDafnyType()) == casper.Util.ARRAY || casper.Util.getDafnyTypeClass(var.getDafnyType()) == casper.Util.OBJECT_ARRAY){
				code += "requires  |" + var.varName + "| == |" + var.varName + "0|\n\t";
			}
		}
		return code;
	}
	
	public static String generateEmitsPreCond(MyWhileExt ext, Set<Variable> loopCounters) {
		String code = "";
		if(casper.Util.getDafnyTypeClass(ext.inputDataSet.getDafnyType()) == casper.Util.OBJECT_ARRAY){
			code += "requires null !in " +  ext.inputDataSet.varName + "\n\t";
			code += "reads " + ext.inputDataSet.varName + "\n\t";
		}
		for(Variable lc : loopCounters){
			code += "requires 0 <= " + lc.varName + " < |" + ext.inputDataSet.varName + "|";
			break;
		}
		return code;
	}
	
	public static String generateMapperPreCond(MyWhileExt ext, Set<Variable> loopCounters) {
		String code = "";
		if(casper.Util.getDafnyTypeClass(ext.inputDataSet.getDafnyType()) == casper.Util.OBJECT_ARRAY){
			code += "requires null !in " +  ext.inputDataSet.varName + "\n\t";
			code += "reads " + ext.inputDataSet.varName + "\n\t";
		}
		for(Variable lc : loopCounters){
			code += "requires 0 <= " + lc.varName + " <= |" + ext.inputDataSet.varName + "|";
			break;
		}
		return code;
	}
	
	public static String generateReduceLemmas(Map<String, List<KvPair>> mapEmits, Map<String, String> reduceExps, int valCount, Set<Variable> outputVars, String reducerType) {
		String code = "";
		String type = casper.Util.getDafnyTypeFromRaw(reducerType);
		for(Variable var : outputVars){
			if(reduceExps.get(var.varName).equals("(val2)")) continue;
			code += "lemma LemmaCSG_"+var.varName+" (casper_a: "+type+", casper_b: "+type+", casper_c: "+type+"<reducer-args-decl>)\n";
		    code += "  ensures reduce_"+var.varName+"(casper_a,casper_b<reducer-args-call>) == reduce_"+var.varName+"(casper_b,casper_a<reducer-args-call>)\n";
		    code += "  ensures reduce_"+var.varName+"(reduce_"+var.varName+"(casper_a,casper_b<reducer-args-call>),casper_c<reducer-args-call>) == reduce_"+var.varName+"(casper_a,reduce_"+var.varName+"(casper_b,casper_c<reducer-args-call>)<reducer-args-call>)\n";
			code += "{}";
		}
		return code;
	}
}