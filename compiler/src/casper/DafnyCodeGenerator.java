package casper;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import polyglot.ast.Node;
import polyglot.ast.While;
import casper.SketchParser.KvPair;
import casper.ast.JavaExt;
import casper.extension.MyStmtExt;
import casper.extension.MyWhileExt;
import casper.types.ArrayAccessNode;
import casper.types.ArrayUpdateNode;
import casper.types.ConditionalNode;
import casper.types.ConstantNode;
import casper.types.CustomASTNode;
import casper.types.FieldNode;
import casper.types.IdentifierNode;
import casper.types.Variable;

public class DafnyCodeGenerator {
	
	public static void generateSummary(int id, Node n, Set<Variable> outputVars, String reducerType) throws IOException {
		// Get node extension
		MyWhileExt ext = (MyWhileExt) JavaExt.ext(n);
		
		// Generate utils file
		String utils = new String(Files.readAllBytes(Paths.get("templates/utils.dfy")), StandardCharsets.UTF_8);
		PrintWriter writer = new PrintWriter("output/utils.dfy", "UTF-8");
		writer.print(utils);
		writer.close();
		
		// Load  template
		String template = new String(Files.readAllBytes(Paths.get("templates/dafny_skeleton.dfy")), StandardCharsets.UTF_8);
		writer = new PrintWriter("output/main_"+reducerType+"_"+id+".dfy", "UTF-8");
		
		// Generate main harness args
		String harnessArgs = generateDafnyHarnessArgs(ext,ext.inputVars,outputVars,ext.loopCounters);
	
		// Generate require statements for dafny main function
		String mainReqStmts = generateRequireStatements(outputVars,ext.wpcValues,ext.inputDataSet);
		
		// Init variables in main
		String initVars = generateVarInit(ext,ext.inputVars,outputVars,ext.loopCounters);
		
		// Generate verification code
		String preC = generatePreCondition(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		MyStmtExt bodyExt = ((MyStmtExt) JavaExt.ext(((While)n).body()));
		
		String loopCond = "";
		for(Variable lc : ext.loopCounters){
			loopCond = "("+lc.varName+"<|"+ext.inputDataSet.varName+"|)";
		}
		
		String loopCondFalse = loopCond; if(ext.condInv) loopCondFalse = "!" + loopCondFalse;
		
		String invariant = generateInvariant(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		/*String lemma = invariant.replace("loopInvariant", "Lemma");*/
		
		String wpc = generateWPC(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters,bodyExt);
		
		String postC = generatePostCondStmt(ext,reducerType,ext.inputVars,outputVars,ext.loopCounters);
		
		// Weakest pre condition value updates
		String wpcInits = generateWPCInits(ext.wpcValues,outputVars,ext.loopCounters);
		
		// 1. Assert loop invariant is true before the loop executes.
		String verifCode = "assert " + preC + ";\n\n\t";
		
		// 2. Assert loop invariant is preserved if the loop continues: I && loop condition is true --> wp(c, I), 
		//    where wp(c, I) is the weakest precondition of the body of the loop with I as the post-condition
		//verifCode += "if(" + invariant + " && " + loopCond + ")\n\t{\n\t\t" + lemma + ";\n\t\t"+wpcInits+"assert " + wpc + ";\n\t}\n\n\t";
		verifCode += "if(" + invariant + " && " + loopCond + ")\n\t{\n\t\t"+wpcInits+"assert " + wpc + ";\n\t}\n\n\t";
		
		// 2. Assert loop invariant implies the post condition if the loop terminates: I && loop condition is false --> POST
		verifCode += "if(" + invariant + " && " + loopCondFalse + ")\n\t{\n\t\tassert " + postC + ";\n\t}";
		
		// Generate args for invariant and post condition
		String invPcArgs = generateInvPcAargs(ext,ext.postConditionArgsOrder.get(reducerType),outputVars,ext.loopCounters,ext.inputVars);
		
		// Generate invariant
		String loopInv = generateLoopInv(ext, reducerType, outputVars, ext.loopCounters, ext.inputVars);
		
		// Generate post condition
		String postCond = generatePostCond(ext, reducerType, outputVars, ext.loopCounters, ext.inputVars);
		
		// Generate mapper function args declaration
		String mapperArgsDecl = generateMapperArgsDecl(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Generate mapper function args call
		String mapperArgsCall = generateMapperArgsCall(ext, ext.loopCounters, ext.inputVars, outputVars);
		String mapperArgsCallInd = generateMapperArgsCallInd(ext, ext.loopCounters, ext.inputVars, outputVars);
		//String mapperArgsCallInd2 = generateMapperArgsCallInd2(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Generate map emits
		String domapEmits = generateMapEmits(ext.mapEmits, mapperArgsCall);
		String emitFuncs = generateEmitFuncs(ext.mapEmits, reducerType);
		
		// Generate terminate condition for map recursion
		String tCond = generateMapTerminateCondition(ext.loopCounters);
		
		// Generate map outputType
		String domapEmitType = generateDomapEmitType(ext.mapEmits,reducerType);
		
		// Do reduce args declaration
		String reducerArgsDecl = generatedReducerArgsDecl(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Do reduce args call
		String reducerArgsCall = generatedReducerArgsCall(ext, ext.loopCounters, ext.inputVars, outputVars);
		
		// Generate do reduce key type
		String doreduceKeyType = generateDoreduceKeyType(ext.mapEmits,reducerType);
		
		// Generate reduce init values
		String reduceInitValues = generateReduceInitValues(ext.mapEmits,ext.initExps,outputVars,reducerType);
		
		// Generate reduce expression
		String reduceExp = generateReduceExp(ext.mapEmits,ext.reduceExps,outputVars,reducerType);
		
		// Generate invariant pre-condition
		String invRequires = generateInvPreCond(ext, outputVars);
		
		// Generate invariant pre-condition
		String emitRequires = generateEmitsPreCond(ext, ext.loopCounters);
		
		// Generate invariant pre-condition
		String mapperRequires = generateMapperPreCond(ext, ext.loopCounters);
		
		// Generate CSG Lemmas
		String csgLemmas = generateReduceLemmas(ext.mapEmits,ext.reduceExps,outputVars);
		
		// Generate User Defined Data Typess
		String UDTs = generateUDTs(ext);
		
		// Plug in generated code into template
		template = template.replace("<harness-args>", harnessArgs);
		template = template.replace("<main-requires>", mainReqStmts);
		template = template.replace("<init-vars>", initVars);
		template = template.replace("<verif-code>", verifCode);
		template = template.replace("<reduce-exp-lemma>", csgLemmas);
		template = template.replace("<inv-pc-args>", invPcArgs);
		template = template.replace("<loop-inv>", loopInv);
		template = template.replace("<post-cond>", postCond);
		template = template.replace("<domap-emits>", domapEmits);
		template = template.replace("<emit-funcs>", emitFuncs);
		template = template.replace("<mapper-args-decl>", mapperArgsDecl);
		template = template.replace("<mapper-args-call>", mapperArgsCall);
		template = template.replace("<mapper-args-call-inductive>", mapperArgsCallInd);	
		template = template.replace("<terminate-condition>", tCond);
		template = template.replace("<domap-emit-type>", domapEmitType);
		template = template.replace("<reduce-exp>",reduceExp);
		template = template.replace("<reducer-args-decl>", reducerArgsDecl);
		template = template.replace("<reducer-args-call>", reducerArgsCall);
		template = template.replace("<doreduce-key-type>", doreduceKeyType);
		template = template.replace("<output-type>", casper.Util.getDafnyTypeFromRaw(reducerType));
		template = template.replace("<reduce-init-value>", reduceInitValues);
		template = template.replace("<inv-requires>", invRequires);
		template = template.replace("<emit-requires>", emitRequires);
		template = template.replace("<mapper-requires>", mapperRequires);
		template = template.replace("<conditional-lemma>", "");
		template = template.replace("<udts>", UDTs);
		
		writer.print(template);
		writer.close();
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
			if(!ext.initVals.containsKey(var.varName)){
				args += ", " + var.varName + "0: " + var.getDafnyType();
			}
		}
		for(Variable var : inputVars){
			if(ext.inputDataCollections.contains(var))
				continue;
			
			if(!ext.initVals.containsKey(var.varName) || (ext.initVals.get(var.varName) instanceof ConstantNode && ((ConstantNode)ext.initVals.get(var.varName)).type == ConstantNode.STRINGLIT)){
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
	
	public static String generateRequireStatements(Set<Variable> outputVars, Map<String, CustomASTNode> wpcValues, Variable inputDataSet) {
		String code = "";
		
		if(casper.Util.getDafnyTypeClass(inputDataSet.getDafnyType()) == casper.Util.OBJECT_ARRAY){
			code += "requires null !in " +  inputDataSet.varName + "\n\t";
		}
		
		for(Variable var : outputVars){
			if(casper.Util.getDafnyTypeClass(var.getDafnyType()) == casper.Util.ARRAY || casper.Util.getDafnyTypeClass(var.getDafnyType()) == casper.Util.OBJECT_ARRAY){
				code += "requires  |" + var.varName + "| == |" + var.varName + "0|\n\t";
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
						if(index instanceof FieldNode){
							code += "requires forall k :: 0 <= k < |" + ((ArrayAccessNode)((FieldNode)index).container).array + "| ==> 0 <= " + ((ArrayAccessNode)((FieldNode)index).container).array + "[k]."+index.toString().substring(index.toString().lastIndexOf(".")+1, index.toString().length())+" < |" + var.varName + "|\n\t";
						}
						else{
							code += "requires 0 <= " + index + " < |" + var.varName + "|\n\t";
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
			if(ext.inputDataCollections.contains(var))
				continue;
			
			if(ext.initVals.containsKey(var.varName)){
				if(!(ext.initVals.get(var.varName) instanceof ConstantNode && ((ConstantNode) ext.initVals.get(var.varName)).type == ConstantNode.STRINGLIT))
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
		
		code = ext.preConditions.get(type).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName)).toString();
		code = code.substring(0,code.length()-1) + ",";
		for(Variable v : inputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v)){
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
		
		invariant = ext.invariants.get(type).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName)).toString();
		invariant = invariant.substring(0,invariant.length()-1) + ",";
		for(Variable v : InputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v)){
				invariant += v.varName + ",";
			}
		}
		for(int i=0; i<ext.constCount; i++){
			invariant += "casperConst" + i + ",";
		}
		invariant = invariant.substring(0,invariant.length()-1) + ")";
		
		return invariant;
	}

	public static String generateWPC(MyWhileExt ext, String outputType, Set<Variable> InputVars, Set<Variable> outputVars, Set<Variable> loopCounters, MyStmtExt bodyExt) {
		String wpc = "";
		
		wpc = bodyExt.preConditions.get(outputType).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName)).toString();
		wpc = wpc.substring(0,wpc.length()-1) + ",";
		for(Variable v : InputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v)){
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
		
		pcond = ext.postConditions.get(outputType).replaceAll("casper_data_set", new IdentifierNode(ext.inputDataSet.varName)).toString();
		pcond = pcond.substring(0,pcond.length()-1) + ",";
		for(Variable v : InputVars){
			if(!outputVars.contains(v) && !loopCounters.contains(v) && !ext.inputDataCollections.contains(v)){
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
		for(String varname : wpcValues.keySet())
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
		}
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
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
				code += ", " + var.varName + ": " + var.getDafnyType();
			}
		}
		for(int i=0; i<ext.constCount; i++){
			code += ", " + "casperConst" + i + ": int";
		}
		
		return code;
	}
	
	public static String generateLoopInv(MyWhileExt ext, String type, Set<Variable> outputVars, Set<Variable> loopCounters, Set<Variable> inputVars) {
		String code = "";
		
		String args = "";
		for(Variable lc : loopCounters){
			code += "0 <= " + lc.varName + " <= |" + ext.inputDataSet.varName + "| &&\n\t";
			args = ext.inputDataSet.varName+","+lc.varName+"0,"+lc.varName;
			break;
		}
		
		for(Variable var : inputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
				args += ","+var.varName;
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += "," + "casperConst" + i;
		}
		
		int index = 1;
		for(Variable var : outputVars){
			if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.PRIMITIVE){
				String reduceCond = ext.mergeExps.get(var.varName).replace("val1", "doreduce(mapper("+args+"),"+index+"<reducer-args-call>)");
				reduceCond = reduceCond.replace("val2", var.varName + "0");
				code += "("+ var.varName + " == (" + reduceCond + ")) &&\n\t";
				index++;
			}
			else if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.ARRAY){
				String reduceCond = ext.mergeExps.get(var.varName).replace("val1", "doreduce(mapper("+args+"),("+index+",k)<reducer-args-call>)");
				reduceCond = reduceCond.replace("val2", var.varName + "0[k]");
				code += "(forall k :: 0 <= k < |" + var.varName  + "| ==> " + var.varName + "[k] == (" + reduceCond + "))	 &&\n\t";
				index++;
			}
		} 
		
		code = code.substring(0,code.length()-5);
		
		return code;
	}
	
	public static String generatePostCond(MyWhileExt ext, String type, Set<Variable> outputVars, Set<Variable> loopCounters, Set<Variable> inputVars) {
		String code = "";
		
		String args = "";
		for(Variable lc : loopCounters){
			code += lc.varName+" == |" + ext.inputDataSet.varName+ "| &&\n\t";
			args = ext.inputDataSet.varName+","+lc.varName+"0,"+lc.varName;
			break;
		}
		
		for(Variable var : inputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
				args += ","+var.varName;
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += "," + "casperConst" + i;
		}
		
		int index = 1;
		for(Variable var : outputVars){
			if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.PRIMITIVE){
				String reduceCond = ext.mergeExps.get(var.varName).replace("val1", "doreduce(mapper("+args+"),"+index+"<reducer-args-call>)");
				reduceCond = reduceCond.replace("val2", var.varName + "0");
				code += "("+ var.varName + " == (" + reduceCond + ")) &&\n\t";
				index++;
			}
			else if(casper.Util.getTypeClass(var.getSketchType()) == casper.Util.ARRAY){
				String reduceCond = ext.mergeExps.get(var.varName).replace("val1", "doreduce(mapper("+args+"),("+index+",k)<reducer-args-call>)");
				reduceCond = reduceCond.replace("val2", var.varName + "0[k]");
				code += "(forall k :: 0 <= k < |" + var.varName  + "| ==> " + var.varName + "[k] == (" + reduceCond + "))	 &&\n\t";
				index++;
			}
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
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
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
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
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
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
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
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
				args += ", " + var.varName;
			}
		}
		for(int i=0; i<ext.constCount; i++){
			args += ", " + "casperConst" + i;
		}
		
		return args;
	}
	
	public static String generateMapEmits(Map<String, List<KvPair>> mapEmits, String mapperArgsCall) {
		String code = "";
		int index = 0;
		for(String cond : mapEmits.keySet()){
			for(KvPair kvp : mapEmits.get(cond)){
				code += "emit" + index + "(" + mapperArgsCall + ") + ";
				index++;
			}
		}
		code = code.substring(0,code.length()-3);
		return code;
	}
	
	public static String generateEmitFuncs(Map<String, List<KvPair>> mapEmits, String reducerType) {
		String code = "";
		int index = 0;
		for(String cond : mapEmits.keySet()){
			for(KvPair kvp : mapEmits.get(cond)){
				String emit = "[(";
				if(kvp.keys.size()>1) emit += "(";
				for(Integer i : kvp.keys.keySet()){
					String key = kvp.keys.get(i);
					if(i>1){
						if(reducerType.equals("boolean"))
							key = key.replaceAll("(^|\\s)0($|\\s)", "false").replaceAll("(^|\\s)1($|\\s)", "true");
					}
					emit += key + ",";
				}
				if(kvp.keys.size()>1) emit = emit.substring(0,emit.length()-1) + "),";
				
				if(kvp.values.size()>1) emit += "(";
				for(Integer i : kvp.values.keySet()){
					String value = kvp.values.get(i);
					if(reducerType.equals("boolean"))
						value = value.replaceAll("(^|\\s)0($|\\s)", "false").replaceAll("(^|\\s)1($|\\s)", "true");
					emit += value + ",";
				}
				emit = emit.substring(0,emit.length()-1);
				if(kvp.values.size()>1) emit += ")";
				emit += ")]";
				
				if(cond.equals("noCondition")){
					code += "function emit" + index + "(<mapper-args-decl>) : <domap-emit-type>\n\t" + 
							"<emit-requires>\n\t" +
							"ensures emit"+index+"(<mapper-args-call>) == "+emit+"\n" +
						"{\n\t" +
						  emit + "\n" +
						"}\n\n";
				}
				else{
					code += "function emit" + index + "(<mapper-args-decl>) : <domap-emit-type>\n\t" + 
							"<emit-requires>\n\t" +
							"ensures ("+cond+") ==> (emit"+index+"(<mapper-args-call>) == "+emit+")\n" +
						"{\n\t" +
						  "if "+cond+" then" + emit + "\n\t" +
						  "else []\n" +
						"}\n\n";
				}
				index++;
			}
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
	
	public static String generateDomapEmitType(Map<String, List<KvPair>> mapEmits, String type) {
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
						keyType += casper.Util.getDafnyTypeFromRaw(type)+",";
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
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
				args += ", " + var.varName + ": " + var.getDafnyType();
			}
		}
		return args;
	}
	
	public static String generatedReducerArgsCall(MyWhileExt ext, Set<Variable> loopCounters, Set<Variable> InputVars, Set<Variable> outputVars) {
		String args = "";
		
		for(Variable var : InputVars){
			if(!outputVars.contains(var) && !loopCounters.contains(var) && !ext.inputDataCollections.contains(var)){
				args += ", " + var.varName;
			}
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
	
	private static String generateReduceInitValues(Map<String, List<KvPair>> mapEmits, Map<String, String> initExps, Set<Variable> outputVars, String reducerType) {
		boolean keyIsTuple = false;
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.keys.size()>1) keyIsTuple = true;
			}
		}
		
		String key = "key";
		if(keyIsTuple) key = "key.0";
		
		String code = "";
		int index = 1;
		String val = "";
		for(Variable var : outputVars){
			val = initExps.get(var.varName);
			if(reducerType.equals("boolean"))
				val = initExps.get(var.varName).replace("0", "false").replace("1", "true");
			code += "if " + key + " == " + index + " then " + val + " else "; 
			index++;
		}
		code = code + val;
		return code;
	}
	
	public static String generateReduceExp(Map<String, List<KvPair>> mapEmits, Map<String, String> reduceExps, Set<Variable> outputVars, String reducerType) { 
		boolean keyIsTuple = false;
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.keys.size()>1) keyIsTuple = true;
			}
		}
		
		String key = "key";
		if(keyIsTuple) key = "key.0";
		
		String code = "";
		int index = 1;
		String reduceExp = "";
		for(Variable var : outputVars){
			if(reducerType.equals("boolean"))
				reduceExp = reduceExp.replaceAll("(^|\\s)1($|\\s)", "false").replaceAll("(^|\\s)1($|\\s)", "true");
			reduceExp = reduceExps.get(var.varName);
			reduceExp = reduceExp.replace("val1", "doreduce(input[1..], key<reducer-args-call>)");
			reduceExp = reduceExp.replace("val2", "input[0].1");
			code += "if " + key + " == " + index + " then (" + reduceExp + ") else "; 
			index++;
		}
		code = code + "("+ reduceExp + ")";
		return code;
	}
	
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
	
	public static String generateReduceLemmas(Map<String, List<KvPair>> mapEmits, Map<String, String> reduceExps, Set<Variable> outputVars) {
		String code = "";
		
		boolean keyIsTuple = false;
		for(String conditional : mapEmits.keySet()){
			if(mapEmits.get(conditional).size() > 0){
				KvPair kvp = mapEmits.get(conditional).get(0);
				if(kvp.keys.size()>1) keyIsTuple = true;
			}
		}
		
		String key = "key";
		if(keyIsTuple) key = "key.0";
		
		String reduceExp = "";
		int index = 1;
		for(Variable var : outputVars){
			reduceExp = reduceExps.get(var.varName);
			if(reduceExp.contains("val1")){
				reduceExp = reduceExp.replace("val1", "doreduce(a, key<reducer-args-call>)");
				reduceExp = reduceExp.replaceAll("val2", "doreduce(b, key<reducer-args-call>)");
				 
				code += "lemma LemmaCSG_"+var.varName+" (a: <domap-emit-type>, b: <domap-emit-type>, key: <doreduce-key-type><reducer-args-decl>)\n\t" +
						    "requires "+key+" == " + index + "\n" +
							"ensures doreduce(a+b, key<reducer-args-call>) == ("+reduceExp+")\n" +
						"{\n\t" +
						    "if a != []\n\t" +
						    "{\n\t\t" +
						    	"LemmaCSG_"+var.varName+"(a[1..], b, key<reducer-args-call>);\n\t\t" +
						    	"assert a + b == [a[0]] + (a[1..] + b);\n\t" +
							"}\n" +
						"}\n\n"; 
			}
			index ++;
		}
		return code;
	}

}
