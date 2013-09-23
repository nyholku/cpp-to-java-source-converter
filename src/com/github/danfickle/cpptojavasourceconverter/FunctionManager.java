package com.github.danfickle.cpptojavasourceconverter;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.cdt.core.dom.ast.*;
import org.eclipse.cdt.core.dom.ast.cpp.*;

import com.github.danfickle.cpptojavasourceconverter.SourceConverterStage2.CompositeInfo;
import com.github.danfickle.cpptojavasourceconverter.SourceConverterStage2.FieldInfo;
import com.github.danfickle.cpptojavasourceconverter.DeclarationModels.*;
import com.github.danfickle.cpptojavasourceconverter.ExpressionModels.*;
import com.github.danfickle.cpptojavasourceconverter.StmtModels.*;
import com.github.danfickle.cpptojavasourceconverter.VarDeclarations.*;

class FunctionManager 
{
	private final GlobalContext ctx;
	
	public FunctionManager(GlobalContext con) {
		ctx = con;
	}
	
	/**
	 * Gets the type of the return value of a function.
	 * @return
	 */
	private String evalReturnType(IBinding funcBinding) throws DOMException
	{
		if (funcBinding instanceof IFunction)
		{
			IFunction func = (IFunction) funcBinding;
			IFunctionType funcType = func.getType();
			return TypeHelpers.cppToJavaType(funcType.getReturnType(), true, false);
		}

		MyLogger.logImportant("Unexpected binding for return type: " + funcBinding.getClass().getCanonicalName());
		MyLogger.exitOnError();
		return null;
	}
	
	/**
	 * Evaluates the parameters for a function. A SingleVariableDeclaration
	 * contains a type and a name. 
	 */
	private List<MSimpleDecl> evalParameters(IBinding funcBinding) throws DOMException
	{
		List<MSimpleDecl> ret = new ArrayList<MSimpleDecl>();

		if (funcBinding instanceof IFunction)
		{
			IFunction func = (IFunction) funcBinding;
			IParameter[] params = func.getParameters();

			int missingCount = 0;
			for (IParameter param : params)
			{	
				MSimpleDecl var = new MSimpleDecl();
				var.type = TypeHelpers.cppToJavaType(param.getType());

				MyLogger.log("Found param: " + param.getName());

				// Remember C++ can have missing function argument names...
				if (param.getName() == null || param.getName().isEmpty())
					var.name = "missing" + missingCount++;
				else
					var.name = param.getName();

				ret.add(var);
			}
		}
		return ret;
	}
	
	/**
	 * Gets the default expressions for function arguments (null where default is not provided).
	 */
	private List<MExpression> getDefaultValues(IASTFunctionDeclarator func) throws DOMException
	{
		IASTStandardFunctionDeclarator declarator = (IASTStandardFunctionDeclarator) func;
		IASTParameterDeclaration[] params = declarator.getParameters();

		List<MExpression> exprs = new ArrayList<MExpression>();

		for (IASTParameterDeclaration param : params)
		{
			IASTDeclarator paramDeclarator = param.getDeclarator(); 

			if (paramDeclarator.getInitializer() != null)
				exprs.add(ctx.converter.eval1Init(paramDeclarator.getInitializer()));
			else
				exprs.add(null);
		}

		return exprs;
	}
	
	/**
	 * Evaluates a function definition and converts it to Java.
	 */
	void evalFunction(IASTDeclaration declaration) throws DOMException
	{
		IASTFunctionDefinition func = (IASTFunctionDefinition) declaration;
		IBinding funcBinding = func.getDeclarator().getName().resolveBinding();
		
		CppFunction method = new CppFunction();
		method.name = TypeHelpers.getSimpleName(func.getDeclarator().getName());
		method.isStatic = ((IFunction) funcBinding).isStatic();
		method.retType = evalReturnType(funcBinding);

		boolean isCtor = false, isDtor = false;

		if (method.retType == null || method.retType.equalsIgnoreCase("void"))
		{
			if (method.name.contains("destruct"))
			{
				method.isCtor = true;
			}
			else
			{
				method.isDtor = true;
			}
		}

		method.args.addAll(evalParameters(funcBinding));
		
		ctx.stackMngr.reset();

		// We need this so we know if the return expression needs to be made
		// java boolean.
		ctx.currentReturnType = method.retType;
		
		// eval1Stmt work recursively to generate the function body.
		method.body = (MCompoundStmt) ctx.stmtEvaluator.eval1Stmt(func.getBody());

		// For debugging purposes, we put return type back to null.
		ctx.currentReturnType = null;
		
		// If we have any local variables that are objects, or arrays of objects,
		// we must create an explicit stack so they can be added to it and explicity
		// cleaned up at termination points (return, break, continue, end block).
		if (ctx.stackMngr.getMaxLocalVariableId() != null)
		{
			MLiteralExpression expr = new MLiteralExpression();
			expr.literal = String.valueOf(ctx.stackMngr.getMaxLocalVariableId());

			MNewArrayExpressionObject array = new MNewArrayExpressionObject();
			array.type = "Object";
			array.sizes.add(expr);

			MSimpleDecl decl = new MSimpleDecl();
			decl.name = "__stack";
			decl.initExpr = array; 
			decl.type = "Object";
			
			MDeclarationStmt stmt = new MDeclarationStmt();
			stmt.simple = decl;
			
			method.body.statements.add(0, stmt);
		}

		CompositeInfo info = ctx.converter.getCurrentCompositeInfo();
		List<FieldInfo> fields = ctx.converter.collectFieldsForClass(info.declSpecifier);
		List<MExpression> superInit = null;
		
		if (func instanceof ICPPASTFunctionDefinition)
		{
			// Now check for C++ constructor initializers...
			ICPPASTFunctionDefinition funcCpp = (ICPPASTFunctionDefinition) func;
			ICPPASTConstructorChainInitializer[] chains = funcCpp.getMemberInitializers();

			if (chains != null && chains.length != 0)
			{
				for (ICPPASTConstructorChainInitializer chain : chains)
				{
					// We may have to call a constructor... 
					if ((chain.getMemberInitializerId().resolveBinding() instanceof IVariable &&
						((IVariable)chain.getMemberInitializerId().resolveBinding()).getType() instanceof ICompositeType)) // &&
						//!(eval1Expr(chain.getInitializerValue()) instanceof ClassInstanceCreation))
					{
						MLiteralExpression lit = 
								ModelCreation.createLiteral(TypeHelpers.cppToJavaType(((IVariable) chain.getMemberInitializerId().resolveBinding()).getType()));
						
						MClassInstanceCreation create = new MClassInstanceCreation();
						create.name = lit;
						create.args.addAll(ctx.exprEvaluator.evalExpr(chain.getInitializerValue()));
						
						// Match this initializer with the correct field.
						for (FieldInfo fieldInfo : fields)
						{
							if (chain.getMemberInitializerId().resolveBinding().getName().equals(fieldInfo.field.getName()))
								fieldInfo.init = create;
						}
					}
					else if (chain.getInitializerValue() != null)
					{
						// Match this initializer with the correct field.
						for (FieldInfo fieldInfo : fields)
						{
							if (chain.getMemberInitializerId().resolveBinding().getName().equals(fieldInfo.field.getName()))
								; // TODOfieldInfo.init = eval1Expr(chain.getInitializerValue());
						}
						
						if (info.hasSuper && chain.getMemberInitializerId().resolveBinding().getName().equals(info.superClass))
						{
							superInit = ctx.exprEvaluator.evalExpr(chain.getInitializerValue());
						}
					}
					else
					{
						// TODO...
					}
				}
			}
		}
		
		if (isCtor)
		{
			// This function generates an initializer for all fields that need initializing.
			ctx.converter.generateCtorStatements(fields, method.body);
			
			// If we have a super class, call super constructor.
			if (info.hasSuper)
			{
				MFunctionCallExpression expr = ModelCreation.createFuncCall("super");
				
				if (superInit != null)
					expr.args.addAll(superInit);
				
				method.body.statements.add(0, ModelCreation.createExprStmt(expr));
			}
		}
		else if (isDtor)
		{
			// This will destruct all fields that need destructing.
			ctx.converter.generateDtorStatements(fields, method.body, info.hasSuper);
		}
		
		info.tyd.declarations.add(method);		
		
		// Generates functions for default arguments.
		makeDefaultCalls(func.getDeclarator(), funcBinding);
	}
	
	/**
	 * Builds default argument function calls.
	 * For example:
	 *   int func_with_defaults(int one, int two = 5);
	 * would generate:
	 * 	 int func_with_defaults(int one) {
	 *	     return func_with_defaults(one, 5);
	 *   }
	 */
	void makeDefaultCalls(IASTFunctionDeclarator func, IBinding funcBinding) throws DOMException
	{
		// getDefaultValues will return an item for every parameter.
		// ie. null for params without a default value.
		List<MExpression> defaultValues = getDefaultValues(func);

		// We start from the right because C++ can only have default values at the
		// right and we can stop when we get to a null (meaning there is no more defaults).
		for (int k = defaultValues.size() - 1; k >= 0; k--)
		{
			if (defaultValues.get(k) == null)
				break;

			// Create the method declaration.
			// This will handle void return types.
			CppFunction methodDef = new CppFunction();
			methodDef.name = TypeHelpers.getSimpleName(func.getName());
			methodDef.retType = evalReturnType(funcBinding);
			
			// This gets a parameter variable declaration for each param.
			List<MSimpleDecl> list = evalParameters(funcBinding);

			// Add a param declaration for each param up until we want to use
			// default values (which won't have a param).
			for (int k2 = 0; k2 < k; k2++)
				methodDef.args.add(list.get(k2));

			// This code block simple calls the original method with
			// passed in arguments plus default arguments.
			MFunctionCallExpression method = ModelCreation.createFuncCall(TypeHelpers.getSimpleName(func.getName()));

			// Add the passed in params by name.
			List<String> names = getArgumentNames(funcBinding);
			for (int k3 = 0; k3 < k; k3++)
			{
				method.args.add(ModelCreation.createLiteral(names.get(k3)));
			}

			// Now add the default values.
			List<MExpression> vals = getDefaultValues(func);
			for (int k4 = k; k4 < defaultValues.size(); k4++)
				method.args.add(vals.get(k4));

			MCompoundStmt block = new MCompoundStmt();
			
			// If not a void function, return the result of the method call.
			if (evalReturnType(funcBinding).toString().equals("void"))
			{
				MExprStmt exprStmt = ModelCreation.createExprStmt(method);
				block.statements.add(exprStmt);
			}
			else
			{
				MReturnStmt retu = new MReturnStmt();
				retu.expr = method;
				block.statements.add(retu);
			}

			methodDef.body = block;
			
			ctx.converter.addDeclaration(methodDef);
			ctx.converter.popDeclaration();
		}
	}
	
	/**
	 * Gets the argument names for a function.
	 */
	private List<String> getArgumentNames(IBinding funcBinding) throws DOMException
	{
		List<String> names = new ArrayList<String>();

		if (funcBinding instanceof IFunction)
		{
			IFunction func = (IFunction) funcBinding;
			IParameter[] params = func.getParameters();

			int missingCount = 0;
			for (IParameter param : params)
			{	
				if (param.getName() == null || param.getName().isEmpty())
					names.add("missing" + missingCount++);
				else
					names.add(param.getName());
			}
		}
		return names;
	}
}
