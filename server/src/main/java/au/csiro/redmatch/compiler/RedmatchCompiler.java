/**
 * Copyright CSIRO Australian e-Health Research Centre (http://aehrc.com). All rights reserved. Use
 * is subject to license terms and conditions.
 */

package au.csiro.redmatch.compiler;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.BufferedTokenStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.DiagnosticErrorListener;
import org.antlr.v4.runtime.Lexer;
import org.antlr.v4.runtime.ParserRuleContext;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.antlr.v4.runtime.Token;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.TerminalNode;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.Oid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import au.csiro.redmatch.exceptions.RedmatchException;
import au.csiro.redmatch.grammar.RedmatchGrammar;
import au.csiro.redmatch.grammar.RedmatchGrammar.AttributeContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.AttributePathContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.ConditionContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.DocumentContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.FcBodyContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.FcRuleContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.ReferenceContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.RepeatsClauseContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.ResourceContext;
import au.csiro.redmatch.grammar.RedmatchGrammar.ValueContext;
import au.csiro.redmatch.grammar.RedmatchGrammarBaseVisitor;
import au.csiro.redmatch.grammar.RedmatchLexer;
import au.csiro.redmatch.importer.CompilerException;
import au.csiro.redmatch.model.Annotation;
import au.csiro.redmatch.model.AnnotationType;
import au.csiro.redmatch.model.Field;
import au.csiro.redmatch.model.Field.FieldType;
import au.csiro.redmatch.model.Metadata;
import au.csiro.redmatch.model.grammar.GrammarObject;
import au.csiro.redmatch.model.grammar.redmatch.Attribute;
import au.csiro.redmatch.model.grammar.redmatch.AttributeValue;
import au.csiro.redmatch.model.grammar.redmatch.Body;
import au.csiro.redmatch.model.grammar.redmatch.BooleanValue;
import au.csiro.redmatch.model.grammar.redmatch.CodeLiteralValue;
import au.csiro.redmatch.model.grammar.redmatch.CodeSelectedValue;
import au.csiro.redmatch.model.grammar.redmatch.ConceptLiteralValue;
import au.csiro.redmatch.model.grammar.redmatch.ConceptSelectedValue;
import au.csiro.redmatch.model.grammar.redmatch.ConceptValue;
import au.csiro.redmatch.model.grammar.redmatch.Condition;
import au.csiro.redmatch.model.grammar.redmatch.ConditionExpression;
import au.csiro.redmatch.model.grammar.redmatch.ConditionExpression.ConditionExpressionOperator;
import au.csiro.redmatch.model.grammar.redmatch.ConditionNode;
import au.csiro.redmatch.model.grammar.redmatch.ConditionNode.ConditionNodeOperator;
import au.csiro.redmatch.model.grammar.redmatch.Document;
import au.csiro.redmatch.model.grammar.redmatch.DoubleValue;
import au.csiro.redmatch.model.grammar.redmatch.FieldBasedValue;
import au.csiro.redmatch.model.grammar.redmatch.FieldValue;
import au.csiro.redmatch.model.grammar.redmatch.IntegerValue;
import au.csiro.redmatch.model.grammar.redmatch.InvalidSyntaxException;
import au.csiro.redmatch.model.grammar.redmatch.ReferenceValue;
import au.csiro.redmatch.model.grammar.redmatch.RepeatsClause;
import au.csiro.redmatch.model.grammar.redmatch.Resource;
import au.csiro.redmatch.model.grammar.redmatch.Rule;
import au.csiro.redmatch.model.grammar.redmatch.RuleList;
import au.csiro.redmatch.model.grammar.redmatch.StringValue;
import au.csiro.redmatch.model.grammar.redmatch.UnknownVariableException;
import au.csiro.redmatch.model.grammar.redmatch.Value;
import au.csiro.redmatch.model.grammar.redmatch.Variables;
import au.csiro.redmatch.validation.PathInfo;
import au.csiro.redmatch.validation.RedmatchGrammarValidator;
import au.csiro.redmatch.validation.ValidationResult;

/**
 * The compiler for the Redmatch rules language.
 * 
 * @author Alejandro Metke Jimenez
 *
 */
@Component
public class RedmatchCompiler extends RedmatchGrammarBaseVisitor<GrammarObject> {
  
  /** Logger. */
  private static final Log log = LogFactory.getLog(RedmatchCompiler.class);
  
  /**
   * Url for a FHIR resource. Used to identify "any" references.
   */
  private final static String RESOURCE_URL = "http://hl7.org/fhir/StructureDefinition/Resource";
  
  /**
   * The messages from the compiler in the previous compilation attempt.
   */
  private final List<Annotation> errorMessages = new ArrayList<>();
  
  /**
   * A component used to validate FHIR paths.
   */
  @Autowired
  private RedmatchGrammarValidator validator;
  
  /**
   * The REDCap metadata. Needed here so it is available in the 
   * {@link #visitDocument(DocumentContext)} method.
   */
  private Metadata metadata = null;
  
  /**
   * Pattern to validate FHIR ids.
   */
  private final Pattern fhirIdPattern = Pattern.compile("[A-Za-z0-9\\-\\.]{1,64}");
  
  /**
   * Pattern to validate REDCap form ids.
   */
  private final Pattern redcapIdPattern = Pattern.compile("[a-z][A-Za-z0-9_]*");
  
  /**
   * Pattern to identify Redmatch variables.
   */
  private final Pattern redmatchVariablePattern = Pattern.compile("[$][{][a-zA-Z0-9_]+[}]");
  
  /**
   * Stores the path information for the leaf node - used to validate each value assignment.
   */
  private PathInfo lastInfo = null;
  
  
  /**
   * Compile rules.
   * 
   * @param rulesDocument A rules document.
   * @param metadata The REDCap metadata.
   * 
   * @return A Document object or null if there is an unrecoverable compilation problem.
   */
  public Document compile(String rulesDocument, Metadata metadata) {
    // Clear errors from previous compilations
    errorMessages.clear();
    
    this.metadata = metadata;
    
    final Lexer lexer = new RedmatchLexer(CharStreams.fromString(rulesDocument));
    final RedmatchGrammar parser = new RedmatchGrammar(new BufferedTokenStream(lexer));

    lexer.removeErrorListeners();
    lexer.addErrorListener(new DiagnosticErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
          int charPositionInLine, String msg, RecognitionException e) {
        addError(errorMessages, offendingSymbol != null ? offendingSymbol.toString() : "", 
            line, charPositionInLine, msg);
      }
    });

    parser.removeErrorListeners();
    parser.addErrorListener(new DiagnosticErrorListener() {
      @Override
      public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol, int line,
          int charPositionInLine, String msg, RecognitionException e) {
        addError(errorMessages, offendingSymbol != null ? offendingSymbol.toString() : "", 
            line, charPositionInLine, msg);
      }
      
      /*@Override
      public void reportAmbiguity(Parser recognizer,
                    DFA dfa,
                    int startIndex,
                    int stopIndex,
                    boolean exact,
                    BitSet ambigAlts,
                    ATNConfigSet configs) {
        String format = "reportAmbiguity d=%s: ambigAlts=%s, input='%s'";
        String decision = getDecisionDescription(recognizer, dfa);
        BitSet conflictingAlts = getConflictingAlts(ambigAlts, configs);
        String text = recognizer.getTokenStream().getText(Interval.of(startIndex, stopIndex));
        String message = String.format(format, decision, conflictingAlts, text);
        addError(errorMessages, "" , 0, 0, message);
      }
      
      @Override
      public void reportAttemptingFullContext(Parser recognizer,
                          DFA dfa,
                          int startIndex,
                          int stopIndex,
                          BitSet conflictingAlts,
                          ATNConfigSet configs) {
        String format = "reportAttemptingFullContext d=%s, input='%s'";
        String decision = getDecisionDescription(recognizer, dfa);
        String text = recognizer.getTokenStream().getText(Interval.of(startIndex, stopIndex));
        String message = String.format(format, decision, text);
        addError(errorMessages, "" , 0, 0, message);
      }

      @Override
      public void reportContextSensitivity(Parser recognizer,
                         DFA dfa,
                         int startIndex,
                         int stopIndex,
                         int prediction,
                         ATNConfigSet configs) {
        String format = "reportContextSensitivity d=%s, input='%s'";
        String decision = getDecisionDescription(recognizer, dfa);
        String text = recognizer.getTokenStream().getText(Interval.of(startIndex, stopIndex));
        String message = String.format(format, decision, text);
        addError(errorMessages, "" , 0, 0, message);
      }
      */
    });
    
    final DocumentContext docCtx = parser.document();
    
    // We need to check if the EOF token was matched. If not, then there is a problem.
    final Token finalToken = lexer.getToken();
    if (finalToken.getType() != Token.EOF) {      
      addError(errorMessages, finalToken.getText(), finalToken.getLine(), 
          finalToken.getCharPositionInLine(), "Unexpected token '" + finalToken.getText() + "'.");
    }
    
    String tree = docCtx.toStringTree(parser);
    if (log.isDebugEnabled()) {
      printPrettyLispTree(tree);
    }
    
    try {
      GrammarObject res = docCtx.accept(this);
      final Document doc = (Document) res;
      return doc;
    } catch (Throwable t) {
      log.error("There was an unexpected problem compiling the rules.", t);
      throw new RedmatchException("There was an unexpected problem compiling the rules.", t);
    }
  }
  
  /**
   * Entry point for visitor. This is the only method that should be called.
   * 
   * document
   *   : fcRule*
   * ;
   */
  @Override
  public GrammarObject visitDocument(DocumentContext ctx) {
    final Document res = new Document();
    
    // If parsing produced errors then do not continue
    if (hasErrors()) {
      return res;
    }
    
    for (FcRuleContext rule : ctx.fcRule()) {
      final Variables var = new Variables();
      GrammarObject go =  visitFcRuleInternal(rule, var, this.metadata);
      if (go instanceof Rule) {
        res.getRules().add((Rule) go);
      } else if (go instanceof RuleList) {
        RuleList rl = (RuleList) go;
        res.getRules().addAll(rl.getRules());
      } else {
        throw new RuntimeException("Unexpected type " + go.getClass().getCanonicalName() 
            + ". Expected Rule or RuleList. This should never happen!");
      }
      
    }
    return res;
  }
  
  /**
   * Get compilation messages from previous compilation attempt.
   * 
   * @return
   */
  public List<Annotation> getErrorMessages() {
    return errorMessages;
  }
  
  
  /**
   * Returns the validator.
   * 
   * @return
   */
  public RedmatchGrammarValidator getValidator() {
    return validator;
  }
  
  /**
   * Sets the validator.
   * 
   * @param validator
   */
  public void setValidator(RedmatchGrammarValidator validator) {
    this.validator = validator;
  }
  
  private boolean hasErrors() {
    for (Annotation ann : errorMessages) {
      if (ann.getAnnotationType().equals(AnnotationType.ERROR)) {
        return true;
      }
    }
    return false;
  }
  
  /*
   * fcRule
   *     : repeatsClause? condition fcBody (ELSE fcBody)?
   *     ;
   */
  private GrammarObject visitFcRuleInternal(FcRuleContext ctx, Variables var, Metadata meta) {
    final Token start = ctx.getStart();
    final Token stop = ctx.getStop();
    
    int startRow = 0;
    int startCol = 0;
    int endRow = 0;
    int endCol = 0;
    
    if (start != null) {
      startRow = start.getLine();
      startCol = start.getCharPositionInLine();
    }
    
    if (stop != null) {
      endRow = stop.getLine();
      endCol = stop.getCharPositionInLine() + 
          (stop.getText() != null ? stop.getText().length() : 1);
    } else {
      endRow = startRow;
      endCol = startCol + 1;
    }

    if (ctx.repeatsClause() != null) {
      final RuleList res = new RuleList();
      
      final RepeatsClause rc = visitRepeatsClauseInternal(ctx.repeatsClause());
      for (int i = rc.getStart(); i <= rc.getEnd(); i++) {
        Variables newVar = new Variables(var);
        newVar.addVariable(rc.getVarName(), i);
        res.getRules().add(processRule(ctx, startRow, startCol, endRow, endCol, newVar, meta));
      }
      return res;
    } else {
      return processRule(ctx, startRow, startCol, endRow, endCol, var, meta);
    }
  }
  
  private Rule processRule(FcRuleContext ctx, int startRow, int startCol, 
      int endRow, int endCol, Variables var, Metadata meta) {
    final Rule res = new Rule(startRow, startCol, endRow, endCol);
    if (ctx.condition() != null) {
      final Condition c = (Condition) visitConditionInternal(ctx.condition(), var, meta);
      res.setCondition(c);
    } else {
      errorMessages.add(getAnnotationFromContext(ctx, 
          "Expected a condition but it was null."));
    }
    if (ctx.fcBody().size() > 0) {
      res.setBody(visitFcBodyInternal(ctx.fcBody(0), var, meta));
    } else {
      errorMessages.add(getAnnotationFromContext(ctx, 
          "Expected at least one body but found none."));
    }
    if (ctx.fcBody().size() > 1) {
      res.setElseBody(visitFcBodyInternal(ctx.fcBody(1), var, meta));
    }
    return res;
  }
  
  /*
   * fcBody
   *     : '{' (resource | fcRule)* '}'
   *     ;
   */
  private Body visitFcBodyInternal(FcBodyContext ctx, Variables var, Metadata meta) {
    final Body b = new Body();
    
    for(ResourceContext rc :  ctx.resource()) {
      b.getResources().add(visitResourceInternal(rc, var, meta));
    }
    
    for(FcRuleContext rc : ctx.fcRule()) {
      GrammarObject go = visitFcRuleInternal(rc, var, meta);
      if (go instanceof Rule) {
        b.getRules().add((Rule) go);
      } else if (go instanceof RuleList) {
        b.getRules().addAll(((RuleList) go).getRules());
      }
    }
    
    return b;
  }
  
  /*
   * repeatsClause
   *    : REPEAT OPEN NUMBER DOTDOT NUMBER COLON IDENTIFIER CLOSE
   *    ;
   */
  private RepeatsClause visitRepeatsClauseInternal(RepeatsClauseContext ctx) {
    int start = Integer.parseInt(ctx.NUMBER(0).getText());
    int end = Integer.parseInt(ctx.NUMBER(1).getText());
    final String varName = ctx.ID().getText();
    return new RepeatsClause(start, end, varName);
  }

  private String removeEnds(String s) {
    if (s.length() < 2) {
      return s;
    }
    return s.substring(1, s.length() - 1);
  }
  
  private String processFhirOrRedcapId(ParserRuleContext ctx, Token t, Variables var) {
    // Determine if the token has a Redmatch variable in it and if so replace it with its actual
    // value - this will look like #cmdt_zyg1 or #cmdt_zyg${x} 
    String s = t.getText();
    final Matcher m = redmatchVariablePattern.matcher(s);
    StringBuilder sb = new StringBuilder();
    int start = 0;
    while (m.find()) {
      sb.append(s.substring(start, m.start()));
      String g = m.group();
      String v = g.substring(2, g.length() - 1);
      int val = 0;
      try {
        val = var.getValue(v);
      } catch (UnknownVariableException e) {
        errorMessages.add(getAnnotationFromContext(ctx, e.getLocalizedMessage()));
      }
      sb.append(val);
      start = m.end();
    }
    if (start < s.length()) {
      sb.append(s.substring(start));
    }
    
    return sb.toString();
  }
  
  private String processRedcapId(ParserRuleContext ctx, Token t, Variables var) {
    String text = processFhirOrRedcapId(ctx, t, var);
    if (!redcapIdPattern.matcher(text).matches()) {
      errorMessages.add(getAnnotationFromContext(ctx, "Invalid REDCap id '" + text + "': must match"
          + " this regex: [a-z][A-Za-z0-9_]*"));
    }
    
    if (!metadata.hasField(text)) {
      errorMessages.add(getAnnotationFromContext(ctx, "Field " + text + " does not exist in "
          + "REDCap report."));
    }
    return text;
  }
  
  private String processFhirId(ParserRuleContext ctx, Token t, Variables var) {
    String text = processFhirOrRedcapId(ctx, t, var);
    if (!fhirIdPattern.matcher(text).matches()) {
      errorMessages.add(getAnnotationFromContext(ctx, "Invalid FHIR id '" + text + "': must match"
          + " this regex: [A-Za-z0-9\\-\\.]{1,64}"));
    }
    return text;
  }
  
  /*
   * condition
   *    : NOT condition
   *    | condition AND condition
   *    | condition OR condition
   *    | (TRUE | FALSE)
   *    | (NULL | NOTNULL) OPEN FHIR_OR_REDCAP_ID CLOSE
   *    | VALUE OPEN FHIR_OR_REDCAP_ID CLOSE(EQ | NEQ | LT | GT | LTE | GTE) (STRING | NUMBER)
   *    | OPEN condition CLOSE
   *    ;
   *     
   *     Example:
   *     
   *     VALUE(final_diagnosis_num) > 0
   */
  private GrammarObject visitConditionInternal(ConditionContext ctx, Variables var, Metadata meta) { 
    ParseTree first = ctx.getChild(0);
    if (first instanceof TerminalNode) {
      TerminalNode tn = (TerminalNode) first;
      String text = tn.getSymbol().getText();
      if ("^".equals(text)) {
        final Condition c = (Condition) visitConditionInternal(ctx.condition(0), var, meta);
        c.setNegated(true);
        return c;
      } else if ("TRUE".equals(text)) {
        return new ConditionExpression(true);
      } else if ("FALSE".equals(text)) {
        return new ConditionExpression(false);
      } else if ("NULL".equals(text)) {
        String id = processRedcapId(ctx, ctx.ID().getSymbol(), var);
        return new ConditionExpression(id, true);
      } else if ("NOTNULL".equals(text)) {
        String id = processRedcapId(ctx, ctx.ID().getSymbol(), var);
        return new ConditionExpression(id, false);
      } else if ("VALUE".equals(text)) {
        if (ctx.getChildCount() < 6) {
          errorMessages.add(getAnnotationFromContext(ctx, 
              "Expected at least 6 children but found " + ctx.getChildCount()));
          return null;
        }
        
        String id = processRedcapId(ctx, ctx.ID().getSymbol(), var);
        String ops = ((TerminalNode) ctx.getChild(4)).getText();
        ConditionExpressionOperator op = getOp(ops);
        if (op == null) {
          errorMessages.add(getAnnotationFromContext(ctx, "Unexpected operator " + ops));
        } else if (ctx.STRING() != null) {
          return new ConditionExpression(id, op, removeEnds(ctx.STRING().getText()));
        } else if (ctx.NUMBER() != null) {
          String num = ctx.NUMBER().getText();
          if (num.contains(".")) {
            return new ConditionExpression(id, op, Double.parseDouble(ctx.NUMBER().getText()));
          } else {
            return new ConditionExpression(id, op, Integer.parseInt(ctx.NUMBER().getText()));
          }
        }
      } else if ("(".equals(text)) {
        return (Condition) visitConditionInternal(ctx.condition(0), var, meta);
      } else {
        errorMessages.add(getAnnotationFromContext(ctx, 
            "Expected TRUE, FALSE, NULL, NOTNULL, VALUE or ( but found  " + text));
      }
    } else {
      if (ctx.getChildCount() < 2) {
        errorMessages.add(getAnnotationFromContext(ctx, 
            "Expected at least two children but found " + ctx.getChildCount()));
      }
      ParseTree second = ctx.getChild(1);
      if (second instanceof TerminalNode) {
        TerminalNode tn = (TerminalNode) second;
        String text = tn.getSymbol().getText();
        if ("&".equals(text)) {
          return new ConditionNode((Condition) visitConditionInternal(ctx.condition(0), var, meta),
              ConditionNodeOperator.AND, 
              (Condition) visitConditionInternal(ctx.condition(1), var, meta));
        } else if ("|".equals(text)) {
          return new ConditionNode((Condition) visitConditionInternal(ctx.condition(0), var, meta),
              ConditionNodeOperator.OR, 
              (Condition) visitConditionInternal(ctx.condition(1), var, meta));
        } else {
          errorMessages.add(getAnnotationFromContext(ctx, 
              "Expected & or | but found " + text));
        }
      } else {
        errorMessages.add(getAnnotationFromContext(ctx, 
            "Expected a terminal node but found " + second));
      }
    }
    return new ConditionExpression(false);
  }
  
  private ConditionExpressionOperator getOp(String ops) {
    if ("=".equals(ops)) {
      return ConditionExpressionOperator.EQ;
    } else if ("!=".equals(ops)) {
      return ConditionExpressionOperator.NEQ;
    } else if ("<".equals(ops)) {
      return ConditionExpressionOperator.LT;
    } else if (">".equals(ops)) {
      return ConditionExpressionOperator.GT;
    } else if ("<=".equals(ops)) {
      return ConditionExpressionOperator.LTE;
    } else if (">=".equals(ops)) {
      return ConditionExpressionOperator.GTE;
    } else {
      return null;
    }
  }
  
  /*
   * resource
   *     : RESOURCE LT FHIR_OR_REDCAP_ID GT THEN attribute EQ value (COMMA attribute EQ value)* END
   *     ;
   *   
   *   Example: Encounter<final> -> status = "FINISHED";
   */
  private Resource visitResourceInternal(ResourceContext ctx, Variables var, Metadata meta) {
    final Resource res = new Resource();
    if (ctx == null) {
      return res;
    }
    res.setResourceType(ctx.RESOURCE().getText());
    
    String resourceId = processFhirId(ctx, ctx.ID().getSymbol(), var);
    res.setResourceId(resourceId);
    
    for (int i = 0; i < ctx.attribute().size(); i++) {
      AttributeValue av = new AttributeValue();
      av.setAttributes(visitAttributeInternal(res.getResourceType(), ctx.attribute(i)));
      av.setValue(visitValueInternal(ctx.value(i), var));
      res.getResourceAttributeValues().add(av);
    }

    return res;
  }
  
  /*
   * attributePath
   *   : PATH (OPEN_SQ INDEX CLOSE_SQ)?
   *   ;
   */
  private Attribute visitAttributePathInternal(AttributePathContext ctx) {
    Attribute att = new Attribute();
    att.setName(ctx.PATH().getText());
    if (ctx.INDEX() != null) {
      att.setAttributeIndex(Integer.parseInt(ctx.INDEX().getText()));
    }
    return att;
  }
  
  /*
   * attribute
   *   : ATTRIBUTE_START attributePath (DOT attributePath)*
   *   ;
   */
  private List<Attribute> visitAttributeInternal(String resourceType, AttributeContext ctx) {
    final List<Attribute> res = new ArrayList<>();
    String path = resourceType;
    
    for (AttributePathContext apCtx : ctx.attributePath()) {
      Attribute att = visitAttributePathInternal(apCtx);
      res.add(att);
      
      // Validate attribute
      path = path + "." + att.getName();
      log.debug("Validating path " + path);
      ValidationResult vr = validator.validateAttributePath(path);
      if (!vr.getResult()) {
        for (String msg : vr.getMessages()) {
          errorMessages.add(getAnnotationFromContext(ctx, msg));
        }
        break;
      } else {
        PathInfo info = validator.getPathInfo(path);
        lastInfo = info;
        
        String max = info.getMax();
        if ("*".equals(max)) {
          att.setList(true);
        } else {
          int maxInt = Integer.parseInt(max);
          if (maxInt == 0) {
            errorMessages.add(getAnnotationFromContext(ctx, "Unable to set attribute " 
                + path + " with max cardinality of 0."));
            break;
          } else if (att.hasAttributeIndex() && att.getAttributeIndex().intValue() >= maxInt) {
            // e.g. myAttr[1] would be illegal if maxInt = 1
            errorMessages.add(getAnnotationFromContext(ctx, "Attribute " 
                + att.toString() + " is setting an invalid index (max = " + maxInt + ")."));
            break;
          }
        }
      }
    }
    
    return res;
  }
  
  /*
   * value
   *     : (TRUE | FALSE)
   *     | STRING
   *     | NUMBER
   *     | reference
   *     | CONCEPT_LITERAL
   *     | CODE_LITERAL
   *     | (CONCEPT | CONCEPT_SELECTED | CODE_SELECTED | VALUE ) OPEN FHIR_OR_REDCAP_ID CLOSE
   *     ;
   */
  private Value visitValueInternal(ValueContext ctx, Variables var) {
    
    // Check Redmatch expression is compatible with REDCap field type
    final String errorMsg = "%s cannot be assigned to attribute of type %s";
    final String type = lastInfo.getType();
    
    if (ctx.TRUE() != null) {
      if (!type.equals("boolean")) {
        errorMessages.add(getAnnotationFromContext(ctx, String.format(errorMsg, "Boolean value", 
            type)));
      }
      return new BooleanValue(true);
    } else if (ctx.FALSE() != null) {
      return new BooleanValue(false);
    } else if (ctx.STRING() != null) {
      
      if (!(type.equals("string") || type.equals("markdown") || type.equals("id") 
          || type.equals("uri") || type.equals("oid") || type.equals("uuid") 
          || type.equals("canonical") || type.equals("url"))) {
        errorMessages.add(getAnnotationFromContext(ctx, String.format(errorMsg, "String literal", 
            type)));
      }
      
      String str = removeEnds(ctx.STRING().getText());
      
      if (type.equals("id") && !fhirIdPattern.matcher(str).matches()) {
        errorMessages.add(getAnnotationFromContext(ctx, "FHIR id " + str + " is invalid (it should "
            + "match this regex: [A-Za-z0-9\\-\\.]{1,64})"));
      } else if (type.equals("uri")) {
        try {
          URI.create(str);
        } catch (IllegalArgumentException e) {
          errorMessages.add(getAnnotationFromContext(ctx, "URI " + str + " is invalid: " 
              + e.getLocalizedMessage()));
        }
      } else if (type.equals("oid")) {
        try {
          new Oid(str);
        } catch (GSSException e) {
          errorMessages.add(getAnnotationFromContext(ctx, "OID " + str + " is invalid: " 
              + e.getLocalizedMessage()));
        }
      } else if (type.equals("uuid")) {
        try {
          UUID.fromString(str);
        } catch (IllegalArgumentException e) {
          errorMessages.add(getAnnotationFromContext(ctx, "UUID " + str + " is invalid: " 
              + e.getLocalizedMessage()));
        }
      } else if (type.equals("canonical")) {
        // Can have a version using |
        String uri = null;
        if (str.contains("|")) {
          String[] parts = str.split("[|]");
          if (parts.length != 2) {
            errorMessages.add(getAnnotationFromContext(ctx, "Canonical " + str + " is invalid"));
            uri = str; // to continue with validation
          } else {
            uri = parts[0];
          }
        }
        
        try {
          URI.create(uri);
        } catch (IllegalArgumentException e) {
          errorMessages.add(getAnnotationFromContext(ctx, "Canonical " + str + " is invalid: " 
              + e.getLocalizedMessage()));
        }
        
      } else if (type.equals("url")) {
        try {
          new URL(str);
        } catch (MalformedURLException e) {
          errorMessages.add(getAnnotationFromContext(ctx, "URL " + str + " is invalid: " 
              + e.getLocalizedMessage()));
        }
      }
      
      return new StringValue(str);
    } else if (ctx.NUMBER() != null) {
      String num = ctx.NUMBER().getText();
      if (num.contains(".")) {
        return new DoubleValue(Double.parseDouble(num));
      } else {
        if (!type.equals("integer")) {
          errorMessages.add(getAnnotationFromContext(ctx, String.format(errorMsg, "Integer literal",
              type)));
        }
        return new IntegerValue(Integer.parseInt(num));
      }
    } else if (ctx.reference() != null) {
      // Subclasses of DomainResource
      // TODO: this checks the type is not primitive but complex types can still slip through
      if (!Character.isUpperCase(type.charAt(0))) {
        errorMessages.add(getAnnotationFromContext(ctx, 
            String.format(errorMsg, "Reference", type)));
      }
      return visitReferenceInternal(ctx.reference(), var);
    } else if (ctx.CONCEPT_LITERAL() != null) {
      
      if (!type.equals("Coding") && !type.equals("CodeableConcept")) {
        errorMessages.add(getAnnotationFromContext(ctx, String.format(errorMsg, "Concept literal", 
            type)));
      }
    
      String withoutKeyword = ctx.CONCEPT_LITERAL().getText().substring(15).trim();
      String literal = removeEnds(withoutKeyword);
      String[] parts = literal.split("[|]");
      if (parts.length == 2) {
        try {
          return new ConceptLiteralValue(parts[0], parts[1]);
        } catch (InvalidSyntaxException e) {
          Token t = ctx.CONCEPT_LITERAL().getSymbol();
          addError(errorMessages, t.getText(), t.getLine(), t.getCharPositionInLine(), 
              e.getLocalizedMessage());
          return null;
        }
      } else if(parts.length == 3) {
        try {
          return new ConceptLiteralValue(parts[0], parts[1], removeEnds(parts[2]));
        } catch (InvalidSyntaxException e) {
          Token t = ctx.CONCEPT_LITERAL().getSymbol();
          addError(errorMessages, t.getText(), t.getLine(), t.getCharPositionInLine(), 
              e.getLocalizedMessage());
          return null;
        }
      } else {
        throw new CompilerException("Invalid code literal: " + literal 
            + ". This should not happen!");
      }
    } else if (ctx.CODE_LITERAL() != null) {
      
      if (!type.equals("code")) {
        errorMessages.add(getAnnotationFromContext(ctx, String.format(errorMsg, "Code literal", 
            type)));
      }
      
      String withoutKeyword = ctx.CODE_LITERAL().getText().substring(12).trim();
      String literal = removeEnds(withoutKeyword);
      try {
        return new CodeLiteralValue(literal);
      } catch (InvalidSyntaxException e) {
        Token t = ctx.CODE_LITERAL().getSymbol();
        addError(errorMessages, t.getText(), t.getLine(), t.getCharPositionInLine(), 
            e.getLocalizedMessage());
        return null;
      }
    } else if (ctx.ID() != null) {
      String fieldId = processRedcapId(ctx, ctx.ID().getSymbol(), var);

      // Validate REDCap field exists
      final Field f = metadata.getField(fieldId);
      if (f == null) {
        errorMessages.add(getAnnotationFromContext(ctx, "Field " + fieldId 
            + " does not exist in REDCap."));
      }
      
      final TerminalNode tn = (TerminalNode) ctx.getChild(0);
      final String enumConstant = tn.getText();
      
      FieldBasedValue val = null;
      
      if ("CONCEPT".equals(enumConstant)) {
        val = new ConceptValue(fieldId);
      } else if("CONCEPT_SELECTED".equals(enumConstant)) {
        val = new ConceptSelectedValue(fieldId);
      } else if("CODE_SELECTED".equals(enumConstant)) {
        val = new CodeSelectedValue(fieldId);
      } else if("VALUE".equals(enumConstant)) {
        val = new FieldValue(fieldId);
      } else {
        throw new CompilerException("Unexpected value type " + enumConstant);
      }
      
      // Additional validations depending on the type of value
      
      if (f != null) {
        final FieldType ft = f.getFieldType();
        
        // CONCEPT can only apply to a field of type TEXT, YESNO, DROPDOWN, RADIO, CHECKBOX, 
        // CHECKBOX_OPTION or TRUEFALSE.
        if (val instanceof ConceptValue && !(ft.equals(FieldType.TEXT) 
            || ft.equals(FieldType.YESNO) || ft.equals(FieldType.DROPDOWN) 
            || ft.equals(FieldType.RADIO) || ft.equals(FieldType.DROPDOW_OR_RADIO_OPTION) 
            || ft.equals(FieldType.CHECKBOX) || ft.equals(FieldType.CHECKBOX_OPTION) 
            || ft.equals(FieldType.TRUEFALSE))) {
          errorMessages.add(getAnnotationFromContext(ctx, 
              "The expression CONCEPT can only be used on fields of type TEXT, YESNO, DROPDOWN, "
              + "RADIO, DROPDOW_OR_RADIO_OPTION, CHECKBOX, CHECKBOX_OPTION or TRUEFALSE but field " 
              + fieldId + " is of type " + f.getFieldType()));
        }
        
        // FHIR-35: we no longer need this check - CONCEPT can be used on plain text fields
        // If used on a TEXT field, the field should be connected to a FHIR terminology server.
        //if (val instanceof ConceptValue && ft.equals(FieldType.TEXT) 
        //    && !TextValidationType.FHIR_TERMINOLOGY.equals(tvt)) {
        //  errorMessages.add(getAnnotationFromContext(ctx, 
        //      "The field " + fieldId + " is a text field but it is not validated using a FHIR "
        //          + "terminology server. CONCEPT expressions used on fields of type TEXT require "
        //          + "that the fields are validated using a FHIR terminology server."));
        //}
        
        // CONCEPT_SELECTED can only apply to fields of type DROPDOW and RADIO
        if (val instanceof ConceptSelectedValue && !(ft.equals(FieldType.DROPDOWN) 
            || ft.equals(FieldType.RADIO))) {
          errorMessages.add(getAnnotationFromContext(ctx, "The expression CONCEPT_SELECTED "
              + "can only be used on fields of type DROPDOWN and RADIO but field " + fieldId 
              + " is of type " + f.getFieldType()));
        }
        
        // CODE_SELECTED can only apply to fields of type DROPDOWN and RADIO
        if (val instanceof CodeSelectedValue && !(ft.equals(FieldType.DROPDOWN) 
            || ft.equals(FieldType.RADIO))) {
          errorMessages.add(getAnnotationFromContext(ctx, "The expression CODE_SELECTED can "
              + "only be used on fields of type DROPDOWN and RADIO but field " + fieldId 
              + " is of type " + f.getFieldType()));
        }
      }
      
      return val;
    } else {
      errorMessages.add(getAnnotationFromContext(ctx, "Unexpected value context: " 
          + ctx.toString()));
      return null;
    }
  }
  
  /*
   * reference
   *     : REF OPEN RESOURCE LT FHIR_OR_REDCAP_ID GT CLOSE
   *     ;
   *   
   * Example: REF(Patient<p>)
   */
  private Value visitReferenceInternal(ReferenceContext ctx, Variables var) {
    final String resType = ctx.RESOURCE().getText();
    
    // Validate reference based on target profiles
    final List<String> tgtProfiles = lastInfo.getTargetProfiles();
    
    // Special cases: no target profiles or target profile is Resource
    if (!tgtProfiles.isEmpty() 
        && !(tgtProfiles.size() == 1 && tgtProfiles.get(0).equals(RESOURCE_URL))) {
      // Otherwise we need to make sure that at least one applies
      boolean foundCompatible = false;
      for (String targetProfile : lastInfo.getTargetProfiles()) {
        if (targetProfile.endsWith(resType)) {
          foundCompatible = true;
          break;
        }
      }
      
      if (!foundCompatible) {
        errorMessages.add(getAnnotationFromContext(ctx, "Attribute " + lastInfo.getPath() 
            + " is of type reference but the resource type " + resType + " is incompatible. Valid "
            + "values are: " + String.join(",", lastInfo.getTargetProfiles())));
      }
    }
    
    final ReferenceValue res = new ReferenceValue();
    res.setResourceType(resType);
    String id = processFhirId(ctx, ctx.ID().getSymbol(), var);
    res.setResourceId(id);
    
    return res;
  }

  private void addError(List<Annotation> errors, String token, int line, int charPositionInLine, 
      String msg) {
    errors.add(new Annotation(line, charPositionInLine, line, charPositionInLine + token.length(),
        msg, AnnotationType.ERROR));
  }
  
  private Annotation getAnnotationFromContext(ParserRuleContext ctx, String  msg) {
    final Token start = ctx.getStart();
    final Token stop = ctx.getStop();
    
    int startRow = 0;
    int startCol = 0;
    int endRow = 0;
    int endCol = 0;
    
    if (start != null) {
      startRow = start.getLine();
      startCol = start.getCharPositionInLine();
    }
    
    if (stop != null) {
      endRow = stop.getLine();
      endCol = stop.getCharPositionInLine() + (stop.getText() != null ? 
          stop.getText().length() : 1);
    } else {
      endRow = startRow;
      endCol = startCol + 1;
    }
    
    return new Annotation(startRow, startCol, endRow, endCol, 
        "Compiler error: " + msg, AnnotationType.ERROR);
  }
  
  private static void printPrettyLispTree(String tree) {
    int indentation = 1;
    for (char c : tree.toCharArray()) {
      if (c == '(') {
        if (indentation > 1) {
          System.out.println();
        }
        for (int i = 0; i < indentation; i++) {
          System.out.print("  ");
        }
        indentation++;
      }
      else if (c == ')') {
        indentation--;
      }
      System.out.print(c);
    }
    System.out.println();
  }
}
