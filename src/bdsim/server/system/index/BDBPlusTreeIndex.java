package bdsim.server.system.index;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import org.apache.log4j.Logger;

import bdsim.server.system.BDSystem;
import bdsim.server.system.BDSystemResultSet;
import bdsim.server.system.BDSystemThread;
import bdsim.server.system.BDTable;
import bdsim.server.system.BDTuple;
import bdsim.server.system.concurrency.RollbackException;
//import javafx.scene.Parent;

@SuppressWarnings("unchecked")
public class BDBPlusTreeIndex implements BDIndex {
	
	/** Counter for BDBPlusTreeNode ids */
	protected static int m_insert_id = 1000;
	
	/** Whether we check the validity of the tree after each insert/delete */
	protected static boolean m_checkTree;
	
	/** Whether we should output disk operations used after each insert/delete */
	protected static boolean m_outputDiskUse;
	
	public class BDBPlusTree {
		
		/** 
		 * D-value of the tree. A leaf node can hold 2*d
		 * keys; an internal node can hold 2*d splitters. 
		 */
		private int m_d;
		
		/** 
		 * Is this a primary index? 
		 * cs127 hint: all of our indices are primary. Don't worry about duplicates.
		 */
		private boolean m_isPrimary;
		
		/** Root of the tree */
		private BDBPlusTreeNode m_root;

		public BDBPlusTreeNode getTreeRoot() {
			return m_root;
		}
		
		/** Number of nodes in the whole tree */
		private int m_numNodes;
		
		/** 
		 * Number of completed disk operations
		 * Use these fields for the Analysis & Tuning part
		 * You will need to increment and output these values
		 * when needed. 
		 */
		protected int m_readNumDiskOps = 0; 
		protected int m_writeNumDiskOps = 0;
		
		/** 
		 * Saves the path of most recent call to find(), which prevents
		 * the need for parent pointers.
		 */
		private Vector<BDBPlusTreeNode> m_searchPath;
		
		/** 
		 * All leaves of the tree (for fast sequential access)
		 * cs127 hint: unimportant.
		 */
		private Vector<BDBPlusTreeNode> m_leaves;
		
		/**
		 * Constructs a B+-tree on the BDBPlusTreeIndex.m_keyName field of each
		 * tuple.
		 * 
		 * @param d The d-value of the tree. See m_d
		 * @param isPrimary Is this a primary index?
		 */
		public BDBPlusTree(int d, boolean isPrimary) {
			m_d = d;
			m_isPrimary = isPrimary;
			m_root = new BDBPlusTreeNode(m_d, m_numNodes, true);
			m_leaves = new Vector<BDBPlusTreeNode>();
			m_searchPath = new Vector<BDBPlusTreeNode>();
		}

		public Vector<BDBPlusTreeNode> getLeaves() {
			return this.getLeavesHelper(m_root);
		}

		private Vector<BDBPlusTreeNode> getLeavesHelper(BDBPlusTreeNode  node) {
			Vector<BDBPlusTreeNode> leaves = new Vector<BDBPlusTreeNode>();
			if(node.isLeaf()) {
				leaves.add(node);
			} else {
				for(BDBPlusTreeNode child : node.getChildren()) {
					leaves.addAll(getLeavesHelper(child));
				}
			}
			
			return leaves;
		}

		public int getNumNodes() {
			return m_numNodes;
		}

		public BDBPlusTreeNode getRoot() {
			return m_root;
		}
		
		public Vector<BDBPlusTreeNode> getSearchPath() {
			return m_searchPath;
		}
		
		/**
		 * Delete the tuple t from the tree. This may involve merging or 
		 * redistributing nodes. The call does nothing if the tuple is not in 
		 * the tree. 
		 * 
		 * @param t Tuple to delete
		 * @throws InterruptedException 
		 */
		public void delete(BDTuple t) throws InterruptedException {
			Comparable K = (Comparable)t.getObject(m_keyName);
			if(!find(K)){
				return;
			}
			BDBPlusTreeNode LeafNode = m_tree.getSearchPath().lastElement();
			delete_entry(LeafNode, K, t);
			System.out.printf("read:%d\n",m_readNumDiskOps);
			System.out.printf("write:%d\n",m_writeNumDiskOps);
		}

		
		/**
		 * Delete tuple P associated with key K from leaf node N
		 * 
		 * @param N leaf node
		 * @param K comparable associated with tuple P
		 * @param P tuple to delete
		 * @throws InterruptedException 
		 */
		private void delete_entry(BDBPlusTreeNode N, Comparable K, BDTuple P) throws InterruptedException {
			N.deleteTuple(K, P);
			if (N.equals(m_root)){
				return;
			}
			
			if (N.keyCount() < m_d) {
				BDBPlusTreeNode ParentN = m_searchPath.get(m_searchPath.indexOf(N) - 1);
				int index = ParentN.getChildren().indexOf(N);

				BDBPlusTreeNode Nprime = ParentN.getChild((index == 0) ? 1 : index - 1);
				Comparable Kprime = ParentN.getKey((index == 0) ? 0 : index - 1);
				m_readNumDiskOps++;
				
				boolean prev = index == 0 ? false : true;
				//can merge into one node
				if (N.keyCount() + Nprime.keyCount() <= (m_d * 2)) {
					if (!prev) {
						BDBPlusTreeNode temp = N;
						N = Nprime;
						Nprime = temp;
					}
					
					for (int i = 0; i < N.keyCount(); i++) {
						Nprime.insertTuple(N.getKey(i), N.getTuple(i));
					}
					m_writeNumDiskOps++;
					delete_entry(ParentN, Kprime, N);
				} else {
					if (prev) {
						int m = Nprime.keyCount() - 1;
						ParentN.replace(Kprime, Nprime.getKey(m));
						
						Comparable key = Nprime.getKey(m);
						BDTuple tupleToInsert = Nprime.getTuple(m);
						
						Nprime.deleteTuple(Nprime.getKey(m), Nprime.getTuple(m));
						N.insertTuple(key, tupleToInsert);
						m_writeNumDiskOps+=3;
					} else {
						N.insertTuple(Nprime.getKey(0), Nprime.getTuple(0));
						Nprime.deleteTuple(Nprime.getKey(0), Nprime.getTuple(0));
						ParentN.replace(Kprime, Nprime.getKey(0));
						m_writeNumDiskOps+=3;
					}
				}
			}
		}

		/**
		 * Delete node P associated with key K from inner node N
		 * 
		 * @param P node to delete
		 * @param K comparable associated with node P
		 * @param N inner node
		 * @throws InterruptedException 
		 */
		private void delete_entry(BDBPlusTreeNode N, Comparable K, BDBPlusTreeNode P) throws InterruptedException {
			N.deleteChild(K, P);
			
			if (N.equals(m_root)) {
				if (N.childCount() == 1) {
					m_root = N.getChild(0);
					N.clear();
				}
			} else if (N.childCount() < (m_d + 1)) {
				BDBPlusTreeNode ParentN = m_searchPath.get(m_searchPath.indexOf(N) - 1);
				int index = ParentN.getChildren().indexOf(N);
				
				BDBPlusTreeNode Nprime = ParentN.getChild((index == 0) ? 1 : index - 1);
				Comparable Kprime = ParentN.getKey((index == 0) ? 0 : index - 1);
				m_readNumDiskOps++;
				boolean prev = index == 0 ? false : true;
				//can meerge into one node
				if (N.childCount() + Nprime.childCount() <= (m_d * 2 + 1)) {
					if (!prev) {
						BDBPlusTreeNode temp = N;
						N = Nprime;
						Nprime = temp;
					}
					
					Nprime.insertKey(Kprime);
					for (int i = 0; i < N.keyCount(); i++) {
						Nprime.insertChild(N.getKey(i), N.getChild(i));
					}

					Nprime.insertChild(
							(N.keyCount() == 0) ? Kprime: N.getKey(N.keyCount() - 1), 
							N.getChild(N.childCount() - 1));
					m_writeNumDiskOps++;
					delete_entry(ParentN, Kprime, N);
					N.clear();
				} else {
					if (prev) {
						int m = Nprime.childCount() - 1;
						N.insertChild(Kprime, Nprime.getChild(m));
						ParentN.replace(Kprime, Nprime.getKey(m - 1));
						Nprime.deleteChild(Nprime.getKey(m - 1), Nprime.getChild(m));
						m_writeNumDiskOps += 3;
					} else {
						N.insertChild(Kprime, Nprime.getChild(0));
						Comparable parentKey = Nprime.getKey(0);
						Nprime.deleteChild(Nprime.getKey(0), Nprime.getChild(0));
						ParentN.replace(Kprime, parentKey);
						m_writeNumDiskOps += 3;
					}
				}
			}
		}

			
		/**
		 * Add the tuple t to the tree. This may involve splitting nodes. 
		 * Duplicates are not allowed on a primary index.
		 * 
		 * @param t Tuple to insert
		 * @throws InterruptedException
		 */
		public void insert(BDTuple t) throws InterruptedException {
			Comparable K = (Comparable)t.getObject(m_keyName);
			//check if have duplicates
			if(find(K)){
				return;
			}
			BDBPlusTreeNode LeafNode = m_tree.getSearchPath().lastElement();
			//have space 
			if(LeafNode.keyCount() < 2*m_d){
				LeafNode.insertTuple(K, t);
				m_writeNumDiskOps++;
			}
			//should split
			else{
				BDBPlusTreeNode NewNode = new BDBPlusTreeNode(m_d, LeafNode.getId()+1, true);
				BDBPlusTreeNode block = new BDBPlusTreeNode(m_d+1,0, true);
				//copy all pairs into block
				for(int i = 0;i < LeafNode.keyCount();++i){
					block.insertTuple(LeafNode.getKey(i), LeafNode.getTuple(i));
				}
				block.insertTuple(K, t);
				m_writeNumDiskOps++;
				//delete all pairs in LeafNode
				LeafNode.clear();
				//copy 0 - m_d tuples into LeafNode
				for(int i = 0;i < m_d;++i){
					LeafNode.insertTuple(block.getKey(i), block.getTuple(i));
				}
				m_writeNumDiskOps++;
				//copy m_d+1 - n+1 into newNode
				for(int i = m_d;i < block.keyCount();++i){
					//NewNode.insertKey(block.getKey(i));
					NewNode.insertTuple(block.getKey(i), block.getTuple(i));
				}
				m_writeNumDiskOps++;
				Comparable NewKey = NewNode.getKey(0);
				insert_in_parent(LeafNode, NewKey, NewNode);
			}
			System.out.printf("read:%d\n",m_readNumDiskOps);
			System.out.printf("write:%d\n",m_writeNumDiskOps);
		}
		
		/**
		 * Helper function to add the tuple if the node requires splitting
		 * 
		 * @param n Primary node to insert into
		 * @param kprime Key-value comparable associated with the splitting node
		 * @param nprime Splitter node to insert into
		 * @throws InterruptedException
		 */
		private void insert_in_parent(BDBPlusTreeNode N, Comparable Kprime, BDBPlusTreeNode Nprime) {

			//if N is root
			if(N.equals(m_root)){
				BDBPlusTreeNode NewRoot = new BDBPlusTreeNode(m_d,0, false);
				m_root = NewRoot;
				NewRoot.insertChild(Kprime, N);
				NewRoot.insertChild(Kprime, Nprime);
				m_writeNumDiskOps++;
				return;
			}
			int i;
			for(i = 0;i < m_searchPath.size();++i){
				if (m_searchPath.elementAt(i).equals(N))break;
			}
			BDBPlusTreeNode Parent = m_searchPath.elementAt(i-1);
			//if P has < n key value
			if(Parent.childCount() < m_d*2 +1){
				Parent.insertChild(Kprime, Nprime);
				m_writeNumDiskOps++;
			}
			//need split
			else{
				BDBPlusTreeNode NewParent = new BDBPlusTreeNode(m_d, Parent.getId()+1, false);
				BDBPlusTreeNode block = new BDBPlusTreeNode(m_d+1,0, false);
				//System.out.printf("Parent key count:%d chilren count:%d\n",Parent.keyCount(),Parent.childCount());
				for(i = 0;i < Parent.keyCount();++i){
					block.insertChild(Parent.getKey(i), Parent.getChild(i));

				}
				block.insertChild(Parent.getKey(i-1), Parent.getChild(i));
				block.insertChild(Kprime,Nprime);
				m_writeNumDiskOps++;
				//delete all pairs in Parent
				Parent.clear();
				//copy 0 - m_d tuples into Parent
				for(i = 0;i < m_d;++i){
					Parent.insertChild(block.getKey(i), block.getChild(i));
				}
				Parent.insertChild(block.getKey(i-1), block.getChild(i));
				m_writeNumDiskOps++;
				//!!!!!!!!!!!!!!!
				//copy m_d+1 - n+1 into newParent
				Comparable NewKey = block.getKey(i);
				for(i = m_d+1;i < block.keyCount();++i){
					NewParent.insertChild(block.getKey(i), block.getChild(i));
				}
				NewParent.insertChild(block.getKey(i-1), block.getChild(i));
				m_writeNumDiskOps++;
				insert_in_parent(Parent, NewKey, NewParent);
			}
		}

		/**
		 * Searches the tree. Saves the search path in m_searchPath.
		 * 
		 * @param value to find
		 * @return Whether or not the value is in the tree
		 */
		public boolean find(Comparable value) {
			//clear search path
			m_tree.getSearchPath().clear();
			//get the root and add to path
			BDBPlusTreeNode curr = m_tree.getRoot();
			m_tree.getSearchPath().add(curr);
			int i;
			//get the correct leaf node, the smallest key value greater than v
			while(!curr.isLeaf()){
				for (i = 0; i < curr.keyCount(); i++) {
					if (curr.getKey(i).compareTo(value) > 0) break;
				}
				//Reached the end, nothing greater here
				if (i == curr.keyCount()) 
					curr = curr.getChild(i);
				else
					//the smallest key value greater than v
					curr = curr.getChild(i);
				//add this node into path
				m_tree.getSearchPath().add(curr);
				m_readNumDiskOps++;
			}
			//search value in leaf node
			for (i = 0; i < curr.keyCount(); i++) {
				if (curr.getKey(i).compareTo(value) == 0) {
					m_readNumDiskOps++;
					return true;
				}
			}
			m_readNumDiskOps++;
			return false;
		}
	}
		
	/*
	 * CS127 NOTE: You don't have to worry about the code below this line.
	 * ------------------------------------------------------------------- 
	 */
	
	public Map<Integer, List<BDTuple>> m_delete_shadows;
	
	public Map<Integer, List<BDTuple>> m_insert_shadows;

	private Logger m_logger;
	
	/** Name of the key on which this is an index */
	private String m_keyName;
		
	/** Number of completed disk operations */
	protected int m_readNumDiskOps; 
	protected int m_writeNumDiskOps;
	
	/** The workhorse of the index */
	private BDBPlusTree m_tree;

	/** access the tree **/
	public BDBPlusTree getTree() {
		return m_tree;
	}
	
	/** Tree visualizer (may be null) */
	private BDBPlusTreeVisualizer m_visualizer;

	public BDBPlusTreeIndex(BDTable table, int d, String keyName,
			boolean isPrimary) {
		m_logger = Logger.getLogger(BDBPlusTree.class);
		m_insert_shadows = new HashMap<Integer, List<BDTuple>>();
		m_delete_shadows = new HashMap<Integer, List<BDTuple>>();
		m_tree = new BDBPlusTree(d, isPrimary);
		m_keyName = keyName;
		
		//
		// Do we want to check the validity of each tree after each operation
		//
		if ("true".equals(System.getProperty("pavlo.checkTree"))) {
			BDBPlusTreeIndex.m_checkTree = true;
		}
		//
		// Output disk usage
		//
		if ("true".equals(System.getProperty("pavlo.outputDiskOperations"))) {
			BDBPlusTreeIndex.m_outputDiskUse = true;
		}
	}
	
	/**
	 * Actually DOES operations on the tree which are queued in m_*_shadows.
	 * @throws RollbackException 
	 */
	public void commit(int TID) throws InterruptedException, RollbackException {
		if (m_delete_shadows.get(TID) != null) {
			for (BDTuple t : m_delete_shadows.get(TID)) {
				BDSystem.concurrencyController.writeDataItem(t);
			}
		}
		if (m_insert_shadows.get(TID) != null) {
			for (BDTuple t : m_insert_shadows.get(TID)) {
				BDSystem.concurrencyController.writeDataItem(t);
			}
		}
		
		
		if (m_delete_shadows.get(TID) != null) {
			for (BDTuple t : m_delete_shadows.get(TID)) {
				deleteNow(t);
			}
		}
		if (m_insert_shadows.get(TID) != null) {
			for (BDTuple t : m_insert_shadows.get(TID)) {
				insertNow(t);
			}
		}

		m_delete_shadows.put(TID, null);
		m_insert_shadows.put(TID, null);
	}

	/**
	 * Deletes enqueued operations.
	 */
	public void rollback(int TID) {
		m_insert_shadows.put(TID, null);
		m_delete_shadows.put(TID, null);
	}

	/**
	 * Enqueues the deletion of tuple t from the index.
	 */
	public void delete(BDTuple t) {
		BDSystemThread thread = (BDSystemThread)(Thread.currentThread());
		int TID = thread.getTransactionId();
		if(m_delete_shadows.get(TID) == null) {
			m_delete_shadows.put(TID, new LinkedList<BDTuple>());
		}
		m_delete_shadows.get(TID).add(t);
	}
	
	/**
	 * Actually removes the tuple from the index, ignoring concurrency.
	 */
	public void deleteNow(BDTuple t) throws InterruptedException {
		m_tree.delete(t);
		pokeVisualizer();
	}
	
	/**
	 * Enqueues the insertion of tuple t from the index.
	 */
	public void insert(BDTuple t) {
		BDSystemThread thread = (BDSystemThread)(Thread.currentThread());
		int TID = thread.getTransactionId();
		if(m_insert_shadows.get(TID) == null) {
			m_insert_shadows.put(TID, new LinkedList<BDTuple>());
		}
		m_insert_shadows.get(TID).add(t);
	}

	/**
	 * Actually inserts the tuple into the index, ignoring concurrency.
	 */
	public void insertNow(BDTuple t) throws InterruptedException {
		m_tree.insert(t);
		pokeVisualizer();
	}
	
	public IndexType getIndexType() {
		return IndexType.B_PLUS_TREE;
	}

	public String getKeyName() {
		return m_keyName;
	}
	
	public BDBPlusTreeNode getRoot() {
		return m_tree.getRoot();
	}

	/**
	 * Note: this method makes a copy of the tuples, leaving the caller free to
	 * mess with these tuples.
	 * 
	 * @return All tuples in the index
	 * @throws RollbackException 
	 */
	public BDSystemResultSet getAllTuples() throws InterruptedException, RollbackException {
		
		BDSystemThread thread = (BDSystemThread)(Thread.currentThread());
		int TID = thread.getTransactionId();
		
		BDSystemResultSet result = new BDSystemResultSet();

		for (BDBPlusTreeNode node : m_tree.getLeaves()) {
			for (int i = 0; i < node.keyCount(); i++) {

				m_logger.debug("Values: " + node.keyCount());
				m_logger.debug("Getting a tuple...");
				m_logger.debug(node.getTuple(i));
				
				if(m_delete_shadows.get(TID) == null ||
				 !(m_delete_shadows.get(TID).contains(node.getTuple(i)))) {
					result.addRow(node.getTuple(i));
				}
			}
		}
		if(m_insert_shadows.get(TID) != null) {
			for(BDTuple tx : m_insert_shadows.get(TID)) {
				result.addRow(tx);
			}
		}
		return result;
	}
	
	/**
	 * @return All tuples in the index right now, ignoring shadows 
	 * @throws RollbackException 
	 */
	public BDSystemResultSet getAllTuplesUnchecked() {

		BDSystemResultSet result = new BDSystemResultSet();

		for (BDBPlusTreeNode node : m_tree.getLeaves()) {
			for (int i = 0; i < node.keyCount(); i++) {
				try {
					result.addRow(node.getTuple(i));
				} catch (InterruptedException e) {
					e.printStackTrace();
				} catch (RollbackException e) {
					e.printStackTrace();
				}
			}
		}
		return result;
	}
	

	/**
	 * Selects all tuples from the index that fall within a given range in some
	 * field.
	 * 
	 * @param rtype The type of range (less-than, greater-than, etc)
	 * @param field The field on which to filter 
	 * @param value The value to be compared against (i.e. the range's bound)
	 * @return All tuples in the range
	 * @throws RollbackException 
	 */
	public BDSystemResultSet getTuplesByRange(RangeType rtype, String field,
			Comparable value) throws InterruptedException, RollbackException {
		Collections.sort(m_tree.getLeaves());
		BDSystemThread thread = (BDSystemThread)(Thread.currentThread());
		int TID = thread.getTransactionId();
		BDSystemResultSet result = new BDSystemResultSet();
		BDBPlusTreeNode currentNode;
		boolean moreNodes = false;
		int lastIndex = 0;

		switch (rtype) {
		case GT:
			m_tree.find(value);
			currentNode = m_tree.getSearchPath().lastElement();
			//curr is now the leaf node that should have value
			for (int i = 0; i < currentNode.keyCount(); i++) {
				if (currentNode.getKey(i) == null) continue;
				if (currentNode.getKey(i).compareTo(value) > 0) {
					result.addRow(currentNode.getTuple(i));
					if (i == (currentNode.keyCount() - 1)) {
						moreNodes = true;
					}
				}
			}
			while (moreNodes == true
					&& (m_tree.getLeaves().indexOf(currentNode) < (m_tree.getLeaves().size() - 1))) {
				currentNode = m_tree.getLeaves()
						.elementAt(m_tree.getLeaves().indexOf(currentNode) + 1);
				moreNodes = false;
				for (int i = 0; i < currentNode.keyCount(); i++) {
					if (currentNode.getKey(i) == null) continue;
					if (currentNode.getKey(i).compareTo(value) > 0) {
						result.addRow(currentNode.getTuple(i));
						if (i == (currentNode.keyCount() - 1)) {
							moreNodes = true;
						}
					}
				}
			}
			
			if(m_insert_shadows.get(TID) != null) {
				for(BDTuple tx : m_insert_shadows.get(TID)) {			
					if(tx.getField(field) != null) {
						if (((Comparable)(tx.getField(field))).compareTo(value) > 0) {
							result.addRow(tx);
						}
					}
				}
			}
			break;

		case GTEQ:
			m_tree.find(value);
			currentNode = m_tree.getSearchPath().lastElement();
			//curr is now the leaf node that should have value
			for (int i = 0; i < currentNode.keyCount(); i++) {
				if (currentNode.getKey(i) == null) continue;
				if (currentNode.getKey(i).compareTo(value) >= 0) {
					result.addRow(currentNode.getTuple(i));
					if (i == (currentNode.keyCount() - 1)) {
						moreNodes = true;
					}
				}
			}
			while (moreNodes == true
					&& (m_tree.getLeaves().indexOf(currentNode) < (m_tree.getLeaves().size() - 1))) {
				currentNode = m_tree.getLeaves()
						.elementAt(m_tree.getLeaves().indexOf(currentNode) + 1);
				moreNodes = false;
				for (int i = 0; i < currentNode.keyCount(); i++) {
					if (currentNode.getKey(i) == null) continue;
					if (currentNode.getKey(i).compareTo(value) >= 0) {
						result.addRow(currentNode.getTuple(i));
						if (i == (currentNode.keyCount() - 1)) {
							moreNodes = true;
						}
					}
				}
			}
			if(m_insert_shadows.get(TID) != null) {
				for(BDTuple tx : m_insert_shadows.get(TID)) {
					if(tx.getField(field) != null) {
						if (((Comparable)(tx.getField(field))).compareTo(value) >= 0) {
							result.addRow(tx);
						}
					}
				}
			}
			break;

		case LT:
			System.out.println("LT on primary key: ");
			m_tree.find(value);
			currentNode = m_tree.getSearchPath().lastElement();
			//curr is now the leaf node that should have value
			for (int i = 0; i < currentNode.keyCount(); i++) {
				if (currentNode.getKey(i) == null) continue;
				if (currentNode.getKey(i).compareTo(value) <= 0) {
					//if(currentNode.value(i).compareTo(value) < 0)
						//result.addRow(currentNode.getTuple(i));
					if (i == 0) {
						moreNodes = true;						
						System.out.println("More nodes...");
					}
				}
			}
			lastIndex = m_tree.getLeaves().indexOf(currentNode);
			currentNode = m_tree.getLeaves().firstElement();
			while (moreNodes == true
					&& (m_tree.getLeaves().indexOf(currentNode) <= lastIndex)) {
				System.out.println("Inspecting next node...");
				//currentNode = m_tree.getLeaves().elementAt(m_tree.getLeaves().indexOf(currentNode) + 1);
				moreNodes = false;
				for (int i = 0; i < currentNode.keyCount(); i++) {
					System.out.println("Comparing: " + currentNode.getKey(i) + " to " + value);
					if (currentNode.getKey(i) == null) continue;
					if (currentNode.getKey(i).compareTo(value) < 0) {						
						result.addRow(currentNode.getTuple(i));
						if (i == (currentNode.keyCount() - 1)) {
							moreNodes = true;
							System.out.println("Even more nodes...");
						}
					}
				}
				if(moreNodes) {
					currentNode = m_tree.getLeaves().elementAt(m_tree.getLeaves().indexOf(currentNode) + 1);
				}
			}
			if(m_insert_shadows.get(TID) != null) {
				for(BDTuple tx : m_insert_shadows.get(TID)) {
					if(tx.getField(field) != null) {
						if (((Comparable)(tx.getField(field))).compareTo(value) < 0) {
							result.addRow(tx);
						}
					}
				}
			}
			break;

		case LTEQ:
			System.out.println("LT on primary key: ");
			m_tree.find(value);
			currentNode = m_tree.getSearchPath().lastElement();
			//curr is now the leaf node that should have value
			for (int i = 0; i < currentNode.keyCount(); i++) {
				if (currentNode.getKey(i) == null) continue;
				if (currentNode.getKey(i).compareTo(value) <= 0) {
					//if(currentNode.value(i).compareTo(value) < 0)
						//result.addRow(currentNode.getTuple(i));
					if (i == 0) {
						moreNodes = true;						
						System.out.println("More nodes...");
					}
				}
			}
			lastIndex = m_tree.getLeaves().indexOf(currentNode);
			currentNode = m_tree.getLeaves().firstElement();
			while (moreNodes == true
					&& (m_tree.getLeaves().indexOf(currentNode) <= lastIndex)) {
				System.out.println("Inspecting next node...");
				//currentNode = m_tree.getLeaves().elementAt(m_tree.getLeaves().indexOf(currentNode) + 1);
				moreNodes = false;
				for (int i = 0; i < currentNode.keyCount(); i++) {
					System.out.println("Comparing: " + currentNode.getKey(i) + " to " + value);
					if (currentNode.getKey(i) == null) continue;
					if (currentNode.getKey(i).compareTo(value) <= 0) {
						result.addRow(currentNode.getTuple(i));
						if (i == currentNode.keyCount()) {
							moreNodes = true;
							System.out.println("Even more nodes...");
						}
					}
				}
				if(moreNodes) {
					currentNode = m_tree.getLeaves().elementAt(m_tree.getLeaves().indexOf(currentNode) + 1);
				}
			}
			if(m_insert_shadows.get(TID) != null) {
				for(BDTuple tx : m_insert_shadows.get(TID)) {
					if(tx.getField(field) != null) {
						if (((Comparable)(tx.getField(field))).compareTo(value) <= 0) {
							result.addRow(tx);
						}
					}
				}
			}
			break;

		case NEQ:
			for (BDBPlusTreeNode node : m_tree.getLeaves()) {
				for (int i = 0; i < node.keyCount(); i++) {
					if (node.getKey(i) == null) continue;
					if (value.compareTo(node.getKey(i)) != 0)
						result.addRow(node.getTuple(i));
				}
			}
			if(m_insert_shadows.get(TID) != null) {
				for(BDTuple tx : m_insert_shadows.get(TID)) {
					if(tx.getField(field) != null) {
						if (((Comparable)(tx.getField(field))).compareTo(value) != 0) {
							result.addRow(tx);
						}
					}
				}
			}
			break;
		}

		if(m_delete_shadows.get(TID) != null) {
			for(BDTuple tx : m_delete_shadows.get(TID)) {
				if(result.hasTuple(tx))
					result.remove(tx);
			}
		}
		
		return result;
	}	

	/**
	 * @return All tuples whose [m_keyName] field is equal to [value]
	 * @throws RollbackException 
	 */
	public BDSystemResultSet getTuplesByValue(Comparable value) throws InterruptedException, RollbackException {
		
		BDSystemThread thread = (BDSystemThread)(Thread.currentThread());
		int TID = thread.getTransactionId();
		
		BDSystemResultSet returnSet = new BDSystemResultSet();

		m_tree.getSearchPath().clear();
		BDBPlusTreeNode curr = m_tree.getRoot();
		m_tree.getSearchPath().add(curr);
		int i;
		while (!curr.isLeaf()) {
			assert (curr.keyCount() == (curr.childCount() - 1));
			for (i = 0; i < curr.keyCount(); i++) {
				//if (curr.getValue(i) == null) continue;
				//System.out.println("i: " + i + " out of: " + curr.valueCount());
			
				if (curr.getKey(i).compareTo(value) > 0) break;
			}
			if (i == curr.keyCount()) //Reached the end, nothing greater here
				curr = curr.getChild(i);
			else
				//broke on value(i) > value
				curr = curr.getChild(i);
			m_tree.getSearchPath().add(curr);
		}
		//TODO update disk manager for read

		//curr is now the leaf node that should have value
		for (i = 0; i < curr.keyCount(); i++) {
			//if (curr.getValue(i) == null) continue;
			if (curr.getKey(i).compareTo(value) == 0) {
				returnSet.addRow(curr.getTuple(i));
			}
		}
		
		//TODO check next node
		
		if(m_insert_shadows.get(TID) != null) {
			for(BDTuple tx : m_insert_shadows.get(TID)) {
				if(tx.getField(m_keyName) != null) {
					if(((Comparable)(tx.getField(m_keyName))).compareTo(value) == 0) {
						returnSet.addRow(tx);
					}
				}
			}				
		}
		if(m_delete_shadows.get(TID) != null) {
			for(BDTuple tx : m_delete_shadows.get(TID)) {
				if(returnSet.hasTuple(tx))
					returnSet.remove(tx);
			}
		}
		
		return returnSet;
	}
	
	/**
	 * @return All tuples whose [field] field is equal to [value]
	 * @throws RollbackException 
	 */
	public BDSystemResultSet getTuplesByValue(String field, Comparable value) throws InterruptedException, RollbackException {

		BDSystemThread thread = (BDSystemThread)(Thread.currentThread());
		int TID = thread.getTransactionId();
		
		if (field.equals(m_keyName))
			return this.getTuplesByValue(value);
		
		//System.out.println("It's not a primary key.");
		
		BDTuple t;
		BDSystemResultSet result = new BDSystemResultSet();
		for (BDBPlusTreeNode n : m_tree.getLeaves()) {
			for (int i = 0; i < n.keyCount(); i++) {
				t = n.getTuple(i);
				if (t.getObject(field) == null) continue;
				if (((Comparable) t.getObject(field)).compareTo(value) == 0) {
					result.addRow(t);
				}
			}
		}
		if(m_insert_shadows.get(TID) != null) {
			for(BDTuple tx : m_insert_shadows.get(TID)) {
				if(tx.getField(m_keyName) != null) {
					if(((Comparable)(tx.getField(field))).compareTo(value) == 0) {
						result.addRow(tx);
					}
				}
			}				
		}
		if(m_delete_shadows.get(TID) != null) {
			for(BDTuple tx : m_delete_shadows.get(TID)) {
				if(result.hasTuple(tx))
					result.remove(tx);
			}
		}
		return result;
	}
	
	/**
	 * Delete oldTuple, insert newTuple
	 */
	public void replace(BDTuple oldTuple, BDTuple newTuple) {
		this.delete(oldTuple);
		this.insert(newTuple);
	}

	public void setVisualizer(BDBPlusTreeVisualizer visualizer) {
		m_visualizer = visualizer;
	}
	
	private void pokeVisualizer() {
		if (m_visualizer != null) {
			m_visualizer.treeUpdatedHandler();
		}
	}

	/**
	 * Removes a tuple from either the insert shadows or the delete shadows
	 * 
	 * @param tuple
	 *            The tuple to remove
	 */
	public void abandon(BDTuple tuple) {
		m_insert_shadows.remove(tuple);
		m_delete_shadows.remove(tuple);	
	}

	/**
	 * cs127, ebuzek, 2011
	 * NotYetImplementedException
	 * 
	 * only to mark TODOs in the stencil code
	 */
	private class NotYetImplementedException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		/**
		 * NotYetImplementedException()
		 * 
		 * creates exception, the second last method name of the 
		 * current StackTrace will be in the message
		 */
		public NotYetImplementedException() {
			super(String.format("cs127: Please implement %s.", Thread.currentThread().getStackTrace()[1].getMethodName()));
		}
	}
}