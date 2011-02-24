/*
 * Copyright 2009 University of Zurich, Switzerland
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.evolizer.changedistiller.java;

import java.util.Hashtable;
import java.util.List;
import java.util.Stack;

import org.eclipse.jdt.core.compiler.InvalidInputException;
import org.eclipse.jdt.internal.compiler.ASTVisitor;
import org.eclipse.jdt.internal.compiler.ast.AND_AND_Expression;
import org.eclipse.jdt.internal.compiler.ast.ASTNode;
import org.eclipse.jdt.internal.compiler.ast.AllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.ArrayAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.ArrayInitializer;
import org.eclipse.jdt.internal.compiler.ast.AssertStatement;
import org.eclipse.jdt.internal.compiler.ast.Assignment;
import org.eclipse.jdt.internal.compiler.ast.BinaryExpression;
import org.eclipse.jdt.internal.compiler.ast.Block;
import org.eclipse.jdt.internal.compiler.ast.BreakStatement;
import org.eclipse.jdt.internal.compiler.ast.CaseStatement;
import org.eclipse.jdt.internal.compiler.ast.CastExpression;
import org.eclipse.jdt.internal.compiler.ast.CharLiteral;
import org.eclipse.jdt.internal.compiler.ast.ClassLiteralAccess;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.ast.CompoundAssignment;
import org.eclipse.jdt.internal.compiler.ast.ConditionalExpression;
import org.eclipse.jdt.internal.compiler.ast.ContinueStatement;
import org.eclipse.jdt.internal.compiler.ast.DoStatement;
import org.eclipse.jdt.internal.compiler.ast.DoubleLiteral;
import org.eclipse.jdt.internal.compiler.ast.EmptyStatement;
import org.eclipse.jdt.internal.compiler.ast.EqualExpression;
import org.eclipse.jdt.internal.compiler.ast.ExplicitConstructorCall;
import org.eclipse.jdt.internal.compiler.ast.Expression;
import org.eclipse.jdt.internal.compiler.ast.ExtendedStringLiteral;
import org.eclipse.jdt.internal.compiler.ast.FalseLiteral;
import org.eclipse.jdt.internal.compiler.ast.FloatLiteral;
import org.eclipse.jdt.internal.compiler.ast.ForStatement;
import org.eclipse.jdt.internal.compiler.ast.ForeachStatement;
import org.eclipse.jdt.internal.compiler.ast.IfStatement;
import org.eclipse.jdt.internal.compiler.ast.InstanceOfExpression;
import org.eclipse.jdt.internal.compiler.ast.IntLiteral;
import org.eclipse.jdt.internal.compiler.ast.LabeledStatement;
import org.eclipse.jdt.internal.compiler.ast.LocalDeclaration;
import org.eclipse.jdt.internal.compiler.ast.LongLiteral;
import org.eclipse.jdt.internal.compiler.ast.MessageSend;
import org.eclipse.jdt.internal.compiler.ast.MethodDeclaration;
import org.eclipse.jdt.internal.compiler.ast.NullLiteral;
import org.eclipse.jdt.internal.compiler.ast.OR_OR_Expression;
import org.eclipse.jdt.internal.compiler.ast.PostfixExpression;
import org.eclipse.jdt.internal.compiler.ast.PrefixExpression;
import org.eclipse.jdt.internal.compiler.ast.QualifiedAllocationExpression;
import org.eclipse.jdt.internal.compiler.ast.ReturnStatement;
import org.eclipse.jdt.internal.compiler.ast.StringLiteral;
import org.eclipse.jdt.internal.compiler.ast.StringLiteralConcatenation;
import org.eclipse.jdt.internal.compiler.ast.SwitchStatement;
import org.eclipse.jdt.internal.compiler.ast.SynchronizedStatement;
import org.eclipse.jdt.internal.compiler.ast.ThrowStatement;
import org.eclipse.jdt.internal.compiler.ast.TrueLiteral;
import org.eclipse.jdt.internal.compiler.ast.TryStatement;
import org.eclipse.jdt.internal.compiler.ast.TypeDeclaration;
import org.eclipse.jdt.internal.compiler.ast.UnaryExpression;
import org.eclipse.jdt.internal.compiler.ast.WhileStatement;
import org.eclipse.jdt.internal.compiler.lookup.BlockScope;
import org.eclipse.jdt.internal.compiler.parser.Scanner;
import org.eclipse.jdt.internal.compiler.parser.TerminalTokens;
import org.evolizer.changedistiller.model.classifiers.EntityType;
import org.evolizer.changedistiller.model.classifiers.SourceRange;
import org.evolizer.changedistiller.model.entities.SourceCodeEntity;
import org.evolizer.changedistiller.treedifferencing.Node;

/**
 * Visitor to generate an intermediate tree (general, rooted, labeled, valued tree) out of a method body.
 * 
 * @author fluri
 * 
 */
public class JavaASTBodyTransformer extends ASTVisitor {

    private static final String COLON = ":";
    private List<Comment> fComments;
    private Stack<Node> fNodeStack = new Stack<Node>();
    private String fSource;
    private Scanner fScanner;

    private ASTNode fLastVisitedNode;
    private Node fLastAddedNode;

    private Stack<ASTNode[]> fLastCommentTuples = new Stack<ASTNode[]>();
    private Stack<Node[]> fLastCommentNodeTuples = new Stack<Node[]>();
    private ASTHelper fASTHelper;

    /**
     * Creates a new structure transformer.
     * 
     * @param root
     *            the root node of the tree to generate
     * @param astRoot
     *            the AST root node, necessary for comment attachment
     * @param comments
     *            to attach
     * @param scanner
     *            the scanner with which the AST was created
     * @param astHelper
     *            the helper that helps with conversions for the change history meta model.
     */
    public JavaASTBodyTransformer(
            Node root,
            ASTNode astRoot,
            List<Comment> comments,
            Scanner scanner,
            ASTHelper astHelper) {
        fNodeStack.clear();
        fLastVisitedNode = astRoot;
        fLastAddedNode = root;
        fNodeStack.push(root);
        fComments = comments;
        fScanner = scanner;
        fSource = String.valueOf(scanner.getSource());
        fASTHelper = astHelper;
    }

    /**
     * Prepares node for comment attachment.
     * 
     * @param node
     *            the node to prepare for comment attachment
     */
    public void preVisit(ASTNode node) {
        if (!hasComments() || isUnusableNode(node)) {
            return;
        }
        int i = 0;
        while (i < fComments.size()) {
            Comment comment = fComments.get(i);
            if ((fLastVisitedNode != null) && (fLastVisitedNode.sourceStart() > 0)
                    && (fLastVisitedNode.sourceStart() < comment.sourceStart())
                    && (comment.sourceStart() < node.sourceStart())) {
                String commentString = fSource.substring(comment.sourceStart(), comment.sourceEnd());

                ASTNode[] commentTuple = new ASTNode[]{fLastVisitedNode, comment, node};
                fLastCommentTuples.push(commentTuple);

                Node[] nodeTuple = new Node[2];
                nodeTuple[0] = fLastAddedNode; // preceeding node

                pushValuedNode(comment, commentString);
                pop(comment);
                nodeTuple[1] = fLastAddedNode; // comment
                fLastCommentNodeTuples.push(nodeTuple);

                fComments.remove(i--);
            }
            i++;
        }
    }

    private boolean hasComments() {
        return (fComments != null) && !fComments.isEmpty();
    }

    /**
     * Does the comment to code association for the triple {preceedingNode, comment, succeedingNode}
     * 
     * @param node
     *            succeeding node of the triple
     */
    public void postVisit(ASTNode node) {
        if (isUnusableNode(node)) {
            return;
        }
        if (!fLastCommentTuples.isEmpty() && (node == fLastCommentTuples.peek()[2])) {
            ASTNode preceedingNode = fLastCommentTuples.peek()[0];
            ASTNode commentNode = fLastCommentTuples.peek()[1];
            ASTNode succeedingNode = fLastCommentTuples.peek()[2];

            if ((preceedingNode != null) && (succeedingNode != null)) {
                String preceedingNodeString = getASTString(preceedingNode);
                String succeedingNodeString = getASTString(succeedingNode);
                String commentNodeString = getCommentString(commentNode);

                int rateForPreceeding = 0;
                int rateForSucceeding = 0;

                rateForPreceeding += proximityRating(preceedingNode, commentNode);
                rateForSucceeding += proximityRating(commentNode, succeedingNode);

                if (rateForPreceeding == rateForSucceeding) {
                    rateForPreceeding += wordMatching(preceedingNodeString, commentNodeString);
                    rateForSucceeding += wordMatching(succeedingNodeString, commentNodeString);
                }
                if (rateForPreceeding == rateForSucceeding) {
                    rateForSucceeding++;
                }

                Node[] nodeTuple = fLastCommentNodeTuples.peek();
                if (rateForPreceeding > rateForSucceeding) {
                    nodeTuple[1].addAssociatedNode(nodeTuple[0]);
                    nodeTuple[0].addAssociatedNode(nodeTuple[1]);
                } else {
                    nodeTuple[1].addAssociatedNode(fLastAddedNode);
                    fLastAddedNode.addAssociatedNode(nodeTuple[1]);
                }
            }
            fLastCommentTuples.pop();
            fLastCommentNodeTuples.pop();
        }
    }

    /**
     * Calculates the proximity between the two given {@link ASTNode}. Usually one of the nodes is a comment.
     * 
     * @param nodeOne
     *            to calculate the proximity
     * @param nodeTwo
     *            to calculate the proximity
     * @return <code>2</code> if the comment node is on the same line as the other node, <code>1</code> if they are on
     *         adjacent line, <code>0</code> otherwise (times two)
     */
    private int proximityRating(ASTNode left, ASTNode right) {
        int result = 0;
        ASTNode nodeOne = left;
        ASTNode nodeTwo = right;
        // swap code, if nodeOne is not before nodeTwo
        if ((nodeTwo.sourceStart() - nodeOne.sourceStart()) < 0) {
            ASTNode tmpNode = nodeOne;
            nodeOne = nodeTwo;
            nodeTwo = tmpNode;
        }

        int endOfNodePosition = nodeOne.sourceEnd();

        // comment (nodeTwo) inside nodeOne
        if (endOfNodePosition > nodeTwo.sourceStart()) {

            // find position before comment start
            String findNodeEndTemp = fSource.substring(nodeOne.sourceStart(), nodeTwo.sourceStart());

            // remove white space between nodeOne and comment (nodeTwo)
            int lastNonSpaceChar = findNodeEndTemp.lastIndexOf("[^\\s]");
            if (lastNonSpaceChar > -1) {
                findNodeEndTemp = findNodeEndTemp.substring(lastNonSpaceChar);
            }

            // end position of nodeOne before comment without succeeding white space
            endOfNodePosition = nodeTwo.sourceStart() - findNodeEndTemp.length();
        }
        String betweenOneAndComment = fSource.substring(endOfNodePosition, nodeTwo.sourceStart());

        // Comment is on the same line as code, but node in code
        int positionAfterBracket = betweenOneAndComment.lastIndexOf("}");
        int positionAfterSemicolon = betweenOneAndComment.lastIndexOf(";");
        int sameLinePosition = Math.max(positionAfterBracket, positionAfterSemicolon);
        if (sameLinePosition > -1) {
            betweenOneAndComment = betweenOneAndComment.substring(sameLinePosition + 1, betweenOneAndComment.length());
        }

        // 2 points if on the same line as well as inside the code,
        // i.e. there is no line break between the code and the comment
        String newLine = System.getProperty("line.separator");
        if (betweenOneAndComment.indexOf(newLine) == -1) {
            result += 2;

            // 1 point if on the succeeding line,
            // i.e. only one line break between the code and the comment
        } else if (betweenOneAndComment.replaceFirst(newLine, "").indexOf(newLine) == -1) {
            result++;
        }

        return result * 2;
    }

    /**
     * Calculates the word matching between the candidate and the comment string.
     * 
     * @param candidate
     *            to match with
     * @param comment
     *            to match for
     * @return number of tokens the candidate and comment string share (times 2)
     */
    private int wordMatching(String candidate, String comment) {
        int result = 0;

        // split and tokenize candidate string into a hash table
        Hashtable<String, Integer> tokenMatchTable = new Hashtable<String, Integer>();
        String[] candidateTokens = candidate.split("[\\.\\s]+");
        for (String candidateToken : candidateTokens) {
            if (tokenMatchTable.containsKey(candidateToken)) {
                tokenMatchTable.put(candidateToken, tokenMatchTable.remove(candidateToken) + 1);
            } else {
                tokenMatchTable.put(candidateToken, 1);
            }
        }

        // find comment tokens in candidate tokens;
        // number of occurrences are taken as points
        String[] commentTokens = comment.split("\\s+");
        for (String commentToken : commentTokens) {
            if (tokenMatchTable.containsKey(commentToken)) {
                result += tokenMatchTable.get(commentToken);
            }
        }

        return result * 2;
    }

    private String getASTString(ASTNode node) {
        if (node instanceof CompilationUnitDeclaration) {
            return "";
        }
        String result = node.toString();
        int start = 0;
        if (node instanceof MethodDeclaration) {
            MethodDeclaration method = (MethodDeclaration) node;
            if (method.javadoc != null) {
                start += method.modifiersSourceStart - method.javadoc.sourceStart();
            }
        } else if (node instanceof TypeDeclaration) {
            TypeDeclaration type = (TypeDeclaration) node;
            if (type.javadoc != null) {
                start += type.modifiersSourceStart - type.javadoc.sourceStart();
            }
        }
        return result.substring(start);
    }

    private String getCommentString(ASTNode node) {
        return ((Comment) node).getComment();
    }

    @Override
    public boolean visit(ArrayAllocationExpression arrayAllocationExpression, BlockScope scope) {
        return visitExpression(arrayAllocationExpression, scope);
    }

    @Override
    public void endVisit(ArrayAllocationExpression arrayAllocationExpression, BlockScope scope) {
        endVisitExpression(arrayAllocationExpression, scope);
    }

    @Override
    public boolean visit(ArrayInitializer arrayInitializer, BlockScope scope) {
        return visitExpression(arrayInitializer, scope);
    }

    @Override
    public void endVisit(ArrayInitializer arrayInitializer, BlockScope scope) {
        endVisitExpression(arrayInitializer, scope);
    }

    @Override
    public boolean visit(Assignment assignment, BlockScope scope) {
        return visitExpression(assignment, scope);
    }

    @Override
    public void endVisit(Assignment assignment, BlockScope scope) {
        endVisitExpression(assignment, scope);
    }

    @Override
    public boolean visit(CastExpression castExpression, BlockScope scope) {
        return visitExpression(castExpression, scope);
    }

    @Override
    public void endVisit(CastExpression castExpression, BlockScope scope) {
        endVisitExpression(castExpression, scope);
    }

    @Override
    public boolean visit(CharLiteral charLiteral, BlockScope scope) {
        return visitExpression(charLiteral, scope);
    }

    @Override
    public void endVisit(CharLiteral charLiteral, BlockScope scope) {
        endVisitExpression(charLiteral, scope);
    }

    @Override
    public boolean visit(ClassLiteralAccess classLiteral, BlockScope scope) {
        return visitExpression(classLiteral, scope);
    }

    @Override
    public void endVisit(ClassLiteralAccess classLiteral, BlockScope scope) {
        endVisitExpression(classLiteral, scope);
    }

    @Override
    public boolean visit(FalseLiteral falseLiteral, BlockScope scope) {
        return visitExpression(falseLiteral, scope);
    }

    @Override
    public void endVisit(FalseLiteral falseLiteral, BlockScope scope) {
        endVisitExpression(falseLiteral, scope);
    }

    @Override
    public boolean visit(CompoundAssignment compoundAssignment, BlockScope scope) {
        return visitExpression(compoundAssignment, scope);
    }

    @Override
    public void endVisit(CompoundAssignment compoundAssignment, BlockScope scope) {
        endVisitExpression(compoundAssignment, scope);
    }

    @Override
    public boolean visit(FloatLiteral floatLiteral, BlockScope scope) {
        return visitExpression(floatLiteral, scope);
    }

    @Override
    public void endVisit(FloatLiteral floatLiteral, BlockScope scope) {
        endVisitExpression(floatLiteral, scope);
    }

    @Override
    public boolean visit(DoubleLiteral doubleLiteral, BlockScope scope) {
        return visitExpression(doubleLiteral, scope);
    }

    @Override
    public void endVisit(DoubleLiteral doubleLiteral, BlockScope scope) {
        endVisitExpression(doubleLiteral, scope);
    }

    @Override
    public boolean visit(NullLiteral nullLiteral, BlockScope scope) {
        return visitExpression(nullLiteral, scope);
    }

    @Override
    public void endVisit(NullLiteral nullLiteral, BlockScope scope) {
        endVisitExpression(nullLiteral, scope);
    }

    @Override
    public boolean visit(PostfixExpression postfixExpression, BlockScope scope) {
        return visitExpression(postfixExpression, scope);
    }

    @Override
    public void endVisit(PostfixExpression postfixExpression, BlockScope scope) {
        endVisitExpression(postfixExpression, scope);
    }

    @Override
    public boolean visit(PrefixExpression prefixExpression, BlockScope scope) {
        return visitExpression(prefixExpression, scope);
    }

    @Override
    public void endVisit(PrefixExpression prefixExpression, BlockScope scope) {
        endVisitExpression(prefixExpression, scope);
    }

    @Override
    public boolean visit(AllocationExpression allocationExpression, BlockScope scope) {
        return visitExpression(allocationExpression, scope);
    }

    @Override
    public void endVisit(AllocationExpression allocationExpression, BlockScope scope) {
        endVisitExpression(allocationExpression, scope);
    }

    @Override
    public boolean visit(QualifiedAllocationExpression qualifiedAllocationExpression, BlockScope scope) {
        return visitExpression(qualifiedAllocationExpression, scope);
    }

    @Override
    public void endVisit(QualifiedAllocationExpression qualifiedAllocationExpression, BlockScope scope) {
        endVisitExpression(qualifiedAllocationExpression, scope);
    }

    @Override
    public boolean visit(TrueLiteral trueLiteral, BlockScope scope) {
        return visitExpression(trueLiteral, scope);
    }

    @Override
    public void endVisit(TrueLiteral trueLiteral, BlockScope scope) {
        endVisitExpression(trueLiteral, scope);
    }

    @Override
    // CHECKSTYLE:OFF
    public boolean visit(AND_AND_Expression and_and_Expression, BlockScope scope) {
        // CHECKSTYLE:ON
        return visitExpression(and_and_Expression, scope);
    }

    @Override
    // CHECKSTYLE:OFF
    public void endVisit(AND_AND_Expression and_and_Expression, BlockScope scope) {
        // CHECKSTYLE:ON
        endVisitExpression(and_and_Expression, scope);
    }

    @Override
    public boolean visit(BinaryExpression binaryExpression, BlockScope scope) {
        return visitExpression(binaryExpression, scope);
    }

    @Override
    public void endVisit(BinaryExpression binaryExpression, BlockScope scope) {
        endVisitExpression(binaryExpression, scope);
    }

    @Override
    public boolean visit(ConditionalExpression conditionalExpression, BlockScope scope) {
        return visitExpression(conditionalExpression, scope);
    }

    @Override
    public void endVisit(ConditionalExpression conditionalExpression, BlockScope scope) {
        endVisitExpression(conditionalExpression, scope);
    }

    @Override
    public boolean visit(EqualExpression equalExpression, BlockScope scope) {
        return visitExpression(equalExpression, scope);
    }

    @Override
    public void endVisit(EqualExpression equalExpression, BlockScope scope) {
        endVisitExpression(equalExpression, scope);
    }

    @Override
    public boolean visit(ExtendedStringLiteral extendedStringLiteral, BlockScope scope) {
        return visitExpression(extendedStringLiteral, scope);
    }

    @Override
    public void endVisit(ExtendedStringLiteral extendedStringLiteral, BlockScope scope) {
        endVisitExpression(extendedStringLiteral, scope);
    }

    @Override
    public boolean visit(InstanceOfExpression instanceOfExpression, BlockScope scope) {
        return visitExpression(instanceOfExpression, scope);
    }

    @Override
    public void endVisit(InstanceOfExpression instanceOfExpression, BlockScope scope) {
        endVisitExpression(instanceOfExpression, scope);
    }

    @Override
    public boolean visit(IntLiteral intLiteral, BlockScope scope) {
        return visitExpression(intLiteral, scope);
    }

    @Override
    public void endVisit(IntLiteral intLiteral, BlockScope scope) {
        endVisitExpression(intLiteral, scope);
    }

    @Override
    public boolean visit(LongLiteral longLiteral, BlockScope scope) {
        return visitExpression(longLiteral, scope);
    }

    @Override
    public void endVisit(LongLiteral longLiteral, BlockScope scope) {
        endVisitExpression(longLiteral, scope);
    }

    @Override
    public boolean visit(StringLiteralConcatenation literal, BlockScope scope) {
        return visitExpression(literal, scope);
    }

    @Override
    public void endVisit(StringLiteralConcatenation literal, BlockScope scope) {
        endVisitExpression(literal, scope);
    }

    @Override
    // CHECKSTYLE:OFF
    public boolean visit(OR_OR_Expression or_or_Expression, BlockScope scope) {
        // CHECKSTYLE:ON
        return visitExpression(or_or_Expression, scope);
    }

    @Override
    // CHECKSTYLE:OFF
    public void endVisit(OR_OR_Expression or_or_Expression, BlockScope scope) {
        // CHECKSTYLE:ON
        endVisitExpression(or_or_Expression, scope);
    }

    @Override
    public boolean visit(StringLiteral stringLiteral, BlockScope scope) {
        return visitExpression(stringLiteral, scope);
    }

    @Override
    public void endVisit(StringLiteral stringLiteral, BlockScope scope) {
        endVisitExpression(stringLiteral, scope);
    }

    @Override
    public boolean visit(UnaryExpression unaryExpression, BlockScope scope) {
        return visitExpression(unaryExpression, scope);
    }

    @Override
    public void endVisit(UnaryExpression unaryExpression, BlockScope scope) {
        endVisitExpression(unaryExpression, scope);
    }

    @Override
    public boolean visit(AssertStatement assertStatement, BlockScope scope) {
        preVisit(assertStatement);
        String value = assertStatement.assertExpression.toString();
        if (assertStatement.exceptionArgument != null) {
            value += COLON + assertStatement.exceptionArgument.toString();
        }
        push(
                fASTHelper.convertNode(assertStatement),
                value,
                assertStatement.sourceStart(),
                assertStatement.sourceEnd() + 1);
        return false;
    }

    @Override
    public void endVisit(AssertStatement assertStatement, BlockScope scope) {
        pop(assertStatement);
        postVisit(assertStatement);
    }

    @Override
    public boolean visit(Block block, BlockScope scope) {
        // skip block as it is not interesting
        return true;
    }

    @Override
    public void endVisit(Block block, BlockScope scope) {
        // do nothing
    }

    @Override
    public boolean visit(BreakStatement breakStatement, BlockScope scope) {
        preVisit(breakStatement);
        pushValuedNode(breakStatement, breakStatement.label != null ? String.valueOf(breakStatement.label) : "");
        return false;
    }

    @Override
    public void endVisit(BreakStatement breakStatement, BlockScope scope) {
        pop(breakStatement);
        postVisit(breakStatement);
    }

    @Override
    public boolean visit(ExplicitConstructorCall explicitConstructor, BlockScope scope) {
        preVisit(explicitConstructor);
        pushValuedNode(explicitConstructor, explicitConstructor.toString());
        return false;
    }

    @Override
    public void endVisit(ExplicitConstructorCall explicitConstructor, BlockScope scope) {
        pop(explicitConstructor);
        postVisit(explicitConstructor);
    }

    @Override
    public boolean visit(ContinueStatement continueStatement, BlockScope scope) {
        preVisit(continueStatement);
        pushValuedNode(continueStatement, continueStatement.label != null
                ? String.valueOf(continueStatement.label)
                : "");
        return false;
    }

    @Override
    public void endVisit(ContinueStatement continueStatement, BlockScope scope) {
        pop(continueStatement);
        postVisit(continueStatement);
    }

    @Override
    public boolean visit(DoStatement doStatement, BlockScope scope) {
        preVisit(doStatement);
        pushValuedNode(doStatement, doStatement.condition.toString());
        doStatement.action.traverse(this, scope);
        return false;
    }

    @Override
    public void endVisit(DoStatement doStatement, BlockScope scope) {
        pop(doStatement);
        postVisit(doStatement);
    }

    @Override
    public boolean visit(EmptyStatement emptyStatement, BlockScope scope) {
        preVisit(emptyStatement);
        pushEmptyNode(emptyStatement);
        return false;
    }

    @Override
    public void endVisit(EmptyStatement emptyStatement, BlockScope scope) {
        pop(emptyStatement);
        postVisit(emptyStatement);
    }

    @Override
    public boolean visit(ForeachStatement foreachStatement, BlockScope scope) {
        preVisit(foreachStatement);
        pushValuedNode(foreachStatement, foreachStatement.elementVariable.printAsExpression(0, new StringBuffer())
                .toString() + COLON + foreachStatement.collection.toString());
        foreachStatement.action.traverse(this, scope);
        return false;
    }

    @Override
    public void endVisit(ForeachStatement foreachStatement, BlockScope scope) {
        pop(foreachStatement);
        postVisit(foreachStatement);
    }

    /**
     * Visits an expression.
     * 
     * @param expression
     *            to visit
     * @param scope
     *            in which the expression resides
     * @return <code>true</code> if the children of the expression should be visited, <code>false</code> otherwise.
     */
    public boolean visitExpression(Expression expression, BlockScope scope) {
        preVisit(expression);
        // all expression processed in this method are statements
        // - use printStatement to get the ';' at the end of the expression
        // - extend the length of the statement by 1 to add ';'
        push(
                fASTHelper.convertNode(expression),
                expression.toString() + ';',
                expression.sourceStart(),
                expression.sourceEnd() + 1);
        return false;
    }

    private String getSource(int start, int end) {
        return fSource.substring(start, end + 1);
    }

    /**
     * Ends visiting an expression.
     * 
     * @param expression
     *            to end visit with
     * @param scope
     *            in which the visitor visits
     */
    public void endVisitExpression(Expression expression, BlockScope scope) {
        pop(expression);
        postVisit(expression);
    }

    @Override
    public boolean visit(ForStatement forStatement, BlockScope scope) {
        preVisit(forStatement);
        String value = "";
        if (forStatement.condition != null) {
            value = forStatement.condition.toString();
        }
        pushValuedNode(forStatement, value);
        forStatement.action.traverse(this, scope);
        return false;
    }

    @Override
    public void endVisit(ForStatement forStatement, BlockScope scope) {
        pop(forStatement);
        postVisit(forStatement);
    }

    @Override
    public boolean visit(IfStatement ifStatement, BlockScope scope) {
        preVisit(ifStatement);
        String expression = ifStatement.condition.toString();
        push(EntityType.IF_STATEMENT, expression, ifStatement.sourceStart(), ifStatement.sourceEnd());
        if (ifStatement.thenStatement != null) {
            push(
                    EntityType.THEN_STATEMENT,
                    expression,
                    ifStatement.thenStatement.sourceStart(),
                    ifStatement.thenStatement.sourceEnd());
            ifStatement.thenStatement.traverse(this, scope);
            pop(ifStatement.thenStatement);
        }
        if (ifStatement.elseStatement != null) {
            push(
                    EntityType.ELSE_STATEMENT,
                    expression,
                    ifStatement.elseStatement.sourceStart(),
                    ifStatement.elseStatement.sourceEnd());
            ifStatement.elseStatement.traverse(this, scope);
            pop(ifStatement.elseStatement);
        }
        return false;
    }

    @Override
    public void endVisit(IfStatement ifStatement, BlockScope scope) {
        pop(ifStatement);
        postVisit(ifStatement);
    }

    @Override
    public boolean visit(LabeledStatement labeledStatement, BlockScope scope) {
        preVisit(labeledStatement);
        pushValuedNode(labeledStatement, String.valueOf(labeledStatement.label));
        labeledStatement.statement.traverse(this, scope);
        return false;
    }

    @Override
    public void endVisit(LabeledStatement labeledStatement, BlockScope scope) {
        pop(labeledStatement);
        postVisit(labeledStatement);
    }

    @Override
    public boolean visit(LocalDeclaration localDeclaration, BlockScope scope) {
        preVisit(localDeclaration);
        int start = localDeclaration.type.sourceStart();
        int end = localDeclaration.initialization.sourceEnd();
        push(fASTHelper.convertNode(localDeclaration), localDeclaration.toString(), start, end + 1);
        return false;
    }

    @Override
    public void endVisit(LocalDeclaration localDeclaration, BlockScope scope) {
        pop(localDeclaration);
        postVisit(localDeclaration);
    }

    @Override
    public boolean visit(MessageSend messageSend, BlockScope scope) {
        preVisit(messageSend);
        return visitExpression(messageSend, scope);
    }

    @Override
    public void endVisit(MessageSend messageSend, BlockScope scope) {
        endVisitExpression(messageSend, scope);
        postVisit(messageSend);
    }

    @Override
    public boolean visit(ReturnStatement returnStatement, BlockScope scope) {
        preVisit(returnStatement);
        pushValuedNode(returnStatement, returnStatement.expression != null
                ? returnStatement.expression.toString() + ';'
                : "");
        return false;
    }

    @Override
    public void endVisit(ReturnStatement returnStatement, BlockScope scope) {
        pop(returnStatement);
        postVisit(returnStatement);
    }

    @Override
    public boolean visit(CaseStatement caseStatement, BlockScope scope) {
        preVisit(caseStatement);
        pushValuedNode(
                caseStatement,
                caseStatement.constantExpression != null ? caseStatement.constantExpression.toString() : "default");
        return false;
    }

    @Override
    public void endVisit(CaseStatement caseStatement, BlockScope scope) {
        pop(caseStatement);
        postVisit(caseStatement);
    }

    @Override
    public boolean visit(SwitchStatement switchStatement, BlockScope scope) {
        preVisit(switchStatement);
        pushValuedNode(switchStatement, switchStatement.expression.toString());
        visitNodes(switchStatement.statements, scope);
        return false;
    }

    @Override
    public void endVisit(SwitchStatement switchStatement, BlockScope scope) {
        pop(switchStatement);
        postVisit(switchStatement);
    }

    @Override
    public boolean visit(SynchronizedStatement synchronizedStatement, BlockScope scope) {
        preVisit(synchronizedStatement);
        pushValuedNode(synchronizedStatement, synchronizedStatement.expression.toString());
        return true;
    }

    @Override
    public void endVisit(SynchronizedStatement synchronizedStatement, BlockScope scope) {
        pop(synchronizedStatement);
        postVisit(synchronizedStatement);
    }

    @Override
    public boolean visit(ThrowStatement throwStatement, BlockScope scope) {
        preVisit(throwStatement);
        pushValuedNode(throwStatement, throwStatement.exception.toString() + ';');
        return false;
    }

    @Override
    public void endVisit(ThrowStatement throwStatement, BlockScope scope) {
        pop(throwStatement);
        postVisit(throwStatement);
    }

    @Override
    public boolean visit(TryStatement node, BlockScope scope) {
        preVisit(node);
        pushEmptyNode(node);
        push(EntityType.BODY, "", node.tryBlock.sourceStart(), node.tryBlock.sourceEnd());
        node.tryBlock.traverse(this, scope);
        pop(node.tryBlock);
        if ((node.catchBlocks != null) && (node.catchBlocks.length > 0)) {
            Block lastCatchBlock = node.catchBlocks[node.catchBlocks.length - 1];
            push(EntityType.CATCH_CLAUSES, "", node.tryBlock.sourceEnd + 1, lastCatchBlock.sourceEnd);
            int start = node.tryBlock.sourceEnd();
            for (int i = 0; i < node.catchArguments.length; i++) {
                int catchClauseSourceStart = retrieveStartingCatchPosition(start, node.catchArguments[i].sourceStart);
                push(
                        EntityType.CATCH_CLAUSE,
                        node.catchArguments[i].type.toString(),
                        catchClauseSourceStart,
                        node.catchBlocks[i].sourceEnd);
                node.catchBlocks[i].traverse(this, scope);
                pop(node.catchArguments[i].type);
                start = node.catchBlocks[i].sourceEnd();
            }
            pop(null);
        }
        if (node.finallyBlock != null) {
            push(EntityType.FINALLY, "", node.finallyBlock.sourceStart(), node.finallyBlock.sourceEnd());
            node.finallyBlock.traverse(this, scope);
            pop(node.finallyBlock);
        }
        return false;
    }

    // logic taken from org.eclipse.jdt.core.dom.ASTConverter
    private int retrieveStartingCatchPosition(int start, int end) {
        fScanner.resetTo(start, end);
        try {
            int token;
            while ((token = fScanner.getNextToken()) != TerminalTokens.TokenNameEOF) {
                switch (token) {
                    case TerminalTokens.TokenNamecatch:// 225
                        return fScanner.startPosition;
                }
            }
            // CHECKSTYLE:OFF
        } catch (InvalidInputException e) {
            // CHECKSTYLE:ON
            // ignore
        }
        return -1;
    }

    @Override
    public void endVisit(TryStatement tryStatement, BlockScope scope) {
        pop(tryStatement);
        postVisit(tryStatement);
    }

    @Override
    public boolean visit(WhileStatement whileStatement, BlockScope scope) {
        preVisit(whileStatement);
        push(
                fASTHelper.convertNode(whileStatement),
                whileStatement.condition.toString(),
                whileStatement.sourceStart(),
                whileStatement.sourceEnd);
        whileStatement.action.traverse(this, scope);
        return false;
    }

    @Override
    public void endVisit(WhileStatement whileStatement, BlockScope scope) {
        pop(whileStatement);
        postVisit(whileStatement);
    }

    private void visitNodes(ASTNode[] nodes, BlockScope scope) {
        for (ASTNode element : nodes) {
            element.traverse(this, scope);
        }
    }

    private void pushValuedNode(ASTNode node, String value) {
        push(fASTHelper.convertNode(node), value, node.sourceStart(), node.sourceEnd());
    }

    private void pushEmptyNode(ASTNode node) {
        push(fASTHelper.convertNode(node), "", node.sourceStart(), node.sourceEnd());
    }

    private void push(EntityType label, String value, int start, int end) {
        Node n = new Node(label, value.trim(), new SourceCodeEntity(value.trim(), label, new SourceRange(start, end)));
        getCurrentParent().add(n);
        fNodeStack.push(n);
    }

    private void pop(ASTNode node) {
        fLastVisitedNode = node;
        fLastAddedNode = fNodeStack.pop();
    }

    private Node getCurrentParent() {
        return fNodeStack.peek();
    }

    private boolean isUnusableNode(ASTNode node) {
        return node instanceof Comment;
    }
}
