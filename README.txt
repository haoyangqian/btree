TestScript:

	All scripts are tested under m_d = 2, and all scripts are in testScript folder.

	(1) insert1.txt:
	To test insert tuples in leaf node, no overflow

	(2) insert2.txt:
	To test overflow in leaf node

	(3) insert3.txt
	To test overflow in non-leaf node

	(4) delete1.txt
    	To test delete tuples in leaf node, no underflow

 	(5) delete2.txt
	To test underflow in leaf node, can borrow tuples from siblings

	(6) delete3.txt
	To test underflow in leaf node, should merge with siblings

        (7) delete4.txt
        To test underflow in non-leaf node, can borrow tuples from siblings

	(8) delete5.txt
	To test underflow in non-leaf node,should merge with siblings




B+ tree implementation:

	(1) Find
	    Iteratively go down the tree until we find the leaf node, and save every inner node and the leaf node in searach path.
	    And sequentially search the value V in the leaf node, if find return true, if not, return false. 
	
	(2) Insert
	    insert():
		1.get the primary key of tuple p
		2.use find() to find the key, if not found, return false.
		3.if the node overflow,split the node, and insert_in_parent()

	    insert_in_parent():
		1.if the node is root, crate a new root node.
  		2.find the parent of n
		3.if the parent overflow, split it, and insert_in_parent()

	(3) Delete
	    delete():
		1.find the node
		2.delte_entry(node) 

	    delete_entry(leaf):
		1.delete tuple in this node
		2.if node underflow , check whether the sibling is poor sibling or rich sibling
		3.is poor sibling, merge
		4.if rich sibling, borrow one tuple from sibling
		5.delete(inner node)

	    delete_entry(inner node):
		1.delete children in this node
		2.if node underflow , check whether the sibling is poor sibling or rich sibling
		3.is poor sibling, merge
		4.if rich sibling, borrow one children from sibling
		5.delete(inner node)






No Known Bugs




