package casper.visit;

import java.util.ArrayList;
import java.util.List;

import casper.JavaLibModel;
import casper.ast.JavaExt;
import casper.extension.MyStmtExt;
import casper.extension.MyWhileExt;
import polyglot.ast.ArrayAccess;
import polyglot.ast.Assign;
import polyglot.ast.Binary;
import polyglot.ast.Block;
import polyglot.ast.Call;
import polyglot.ast.Cast;
import polyglot.ast.Expr;
import polyglot.ast.Field;
import polyglot.ast.If;
import polyglot.ast.Local;
import polyglot.ast.LocalDecl;
import polyglot.ast.Node;
import polyglot.ast.Receiver;
import polyglot.ast.Return;
import polyglot.ast.Stmt;
import polyglot.ast.Switch;
import polyglot.ast.Unary;
import polyglot.ast.While;
import polyglot.visit.NodeVisitor;

public class ExtractLoopCounters extends NodeVisitor {
	boolean debug;
	boolean ignore;
	ArrayList<MyWhileExt> extensions;
   
	public ExtractLoopCounters(){
		this.debug = false;
		this.ignore = true;
		this.extensions = new ArrayList<MyWhileExt>();
	}
	
	public NodeVisitor enter(Node parent, Node n){
		// If the node is a loop
		if(n instanceof While){
			// If the loop was marked as interesting
			if(((MyWhileExt)JavaExt.ext(n)).interesting){
				// begin extraction
				this.extensions.add((MyWhileExt)JavaExt.ext(n));
				
				// Mark the first if condition to be ignored. It contains the loop increment
				Stmt loopBody = ((While) n).body();
				if(loopBody instanceof Block){
					List<Stmt> stmts = ((Block) loopBody).statements();
					for(Stmt stmt : stmts){
						// Satement is a conditional
						if(stmt instanceof If){
							Stmt cons = ((If) stmt).consequent();
							Stmt alt = ((If) stmt).alternative(); 
							
							// And consequent or alternative does not contain a break
							if(!casper.Util.containsBreak(cons) && !casper.Util.containsBreak(alt)){
								MyStmtExt stmtext = (MyStmtExt)JavaExt.ext(stmt);
								stmtext.isIncrementBlock = true;
								break;
							}
						}
					}
				}
			}
		}
		else if(n instanceof If){
			// If statement
			MyStmtExt stmtext = (MyStmtExt)JavaExt.ext(n);
			if(stmtext.isIncrementBlock){
				this.ignore = false;
			}
		}
		
		// If we are not extracting, then do nothing
		if(this.extensions.size() == 0 || this.ignore) return this;
		
		if(n instanceof Assign){
			// Assignment statement
			Expr left = ((Assign) n).left();
			
			if(left instanceof ArrayAccess){
				for(MyWhileExt ext : this.extensions){
					// Save the array
					ext.saveLoopCounterVariable(((ArrayAccess)left).array().toString(), ((ArrayAccess)left).array().type().toString(),MyWhileExt.Variable.ARRAY_ACCESS);
				}
			}
			else if(left instanceof Field){
				for(MyWhileExt ext : this.extensions){
					// Save the variable
					ext.saveLoopCounterVariable(left.toString(), left.type().toString(), MyWhileExt.Variable.FIELD_ACCESS);
				}
			}
			else{
				for(MyWhileExt ext : this.extensions){
					// Save the variable
					ext.saveLoopCounterVariable(left.toString(), left.type().toString(),MyWhileExt.Variable.VAR);
				}
			}
		}
		else if(n instanceof Call){
			// Function call
			List<Node> writes = JavaLibModel.extractWrites((Call) n);
			for(Node node : writes){
				if(node instanceof Receiver){
					for(MyWhileExt ext : extensions)
						ext.saveLoopCounterVariable(node.toString(),((Receiver)node).type().toString(),MyWhileExt.Variable.VAR);
				}
				else if(node instanceof Local){
					for(MyWhileExt ext : this.extensions){
						ext.saveLoopCounterVariable(ext.toString(),node.toString(),MyWhileExt.Variable.VAR);
					}
				}
			}
		}
		
		return this;
	}
	
	@Override
	public Node leave(Node old, Node n, NodeVisitor v){
		// If the node is a loop
		if(n instanceof While){
			// If the loop was marked as interesting
			if(((MyWhileExt)JavaExt.ext(n)).interesting){
				if(debug){
					System.err.println("Loop Counters:\n"+((MyWhileExt)JavaExt.ext(n)).loopCounters.toString());
				}
				
				this.extensions.remove(((MyWhileExt)JavaExt.ext(n)));
			}
		}
		else if(n instanceof If){
			MyStmtExt stmtext = (MyStmtExt)JavaExt.ext(n);
			if(stmtext.isIncrementBlock)
				this.ignore = true;
		}
       
		return n;
	}
	
	@Override
	public void finish(){
		if(debug)
			System.err.println("\n************* Finished loop counter extraction complier pass *************");
	}
}