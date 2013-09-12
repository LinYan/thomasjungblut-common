package de.jungblut.classification.tree;

import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;

import de.jungblut.math.DoubleVector;

public interface TreeNode {

  /**
   * @return predicts the index of the outcome, or -1 if not known. In the
   *         binary case, 0 and 1 are used to distinguish.
   */
  public int predict(DoubleVector features);

  /**
   * Transforms this node to byte code, given a visitor that already starts
   * containing the methods and a label that must be jumped to in case of a
   * return.
   */
  public void transformToByteCode(MethodVisitor visitor, Label returnLabel);

}
