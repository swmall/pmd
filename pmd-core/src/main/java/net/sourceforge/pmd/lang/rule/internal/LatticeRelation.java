/*
 * BSD-style license; for more info see http://pmd.sourceforge.net/license.html
 */

package net.sourceforge.pmd.lang.rule.internal;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.pcollections.HashTreePSet;
import org.pcollections.PSet;

import net.sourceforge.pmd.internal.util.AssertionUtil;
import net.sourceforge.pmd.lang.rule.internal.GraphUtils.DotColor;
import net.sourceforge.pmd.util.CollectionUtil;

/**
 * Indexes data of type {@code <V>} with keys of type {@code <K>}, where
 * a partial order exists between the keys. The internal representation
 * is a directed acyclic graph on {@code <K>}. The value associated to
 * a key is the recursive union of the values of all the keys it covers.
 *
 * <p>The internal structure only allows <i>some</i> keys to be queried
 * among all keys encountered.
 *
 * <p>An instance can be in either write-only or read-only mode, toggled
 * by {@link #makeReadable()} and {@link #makeWritable()}. This avoids
 * doing the invalidation every time we index a node, we do it in bulk.
 *
 * @param <K> Type of keys, must have a corresponding {@link TopoOrder},
 *            must be suitable for use as a map key (immutable, consistent
 *            equals/hashcode)
 * @param <V> Type of values
 */
class LatticeRelation<K, @NonNull V> {

    private final Predicate<? super K> queryKeySelector;
    private final TopoOrder<K> keyOrder;
    private final Function<? super K, String> keyToString;

    /** Those nodes that can be queried (match the filter). */
    private final Map<K, QueryNode> qNodes = new HashMap<>();

    /**
     * Those nodes that were added explicitly through #put, but may not be queried.
     * These can be fetched efficiently, which is nice since we're trying to index
     * the same keys over and over. If the node has no query node parent, then it's
     * mapped to the {@link #blackHole}, which ignores incoming values.
     */
    private final Map<K, LNode> leaves = new HashMap<>();
    private final LNode blackHole = new BlackHoleNode();

    private State state = State.WRITE_ALLOWED;

    /**
     * Creates a new relation with the given configuration.
     *
     * @param keyOrder         Partial order generating the lattice
     * @param queryKeySelector Filter determining which keys can be queried
     *                         through {@link #get(Object)}
     * @param keyToString      Strategy to render keys when dumping the lattice to a graph
     */
    LatticeRelation(TopoOrder<K> keyOrder,
                    Predicate<? super K> queryKeySelector,
                    Function<? super K, String> keyToString) {
        this.keyOrder = keyOrder;
        this.queryKeySelector = queryKeySelector;
        this.keyToString = keyToString;
    }

    /**
     * Works like the other constructor, the filter being containment
     * in the given query set. This means, only keys that are in this
     * set may be queried.
     */
    LatticeRelation(TopoOrder<K> keyOrder,
                    Set<? extends K> querySet,
                    Function<? super K, String> keyToString) {
        this.keyOrder = keyOrder;
        this.queryKeySelector = querySet::contains;
        this.keyToString = keyToString;

        for (K k : querySet) {
            put(k, null);
        }

        // Since we know in advance which nodes are in the lattice, we
        // can perform this optimisation.
        // This reduces the number of edges, so improves the number of
        // values that can be cached immediately (removes some diamond situations).
        transitiveReduction();
    }

    /**
     * Adds the val to the node corresponding to the [key], creating it
     * if needed. If the key matches the filter, a QueryNode is created.
     * Otherwise, either a LeafNode (if there is some QueryNode that cares),
     * or the key is linked to the black hole. This is only done the first
     * time we encounter the key, which means subsequently, #get and #put
     * access will be "constant time" (uses one of the maps).
     *
     * <p>All successors of the key are recursively added to the structure.
     *
     * @param pred Predecessor node (in recursive calls, this is set,
     *             to link the predecessors to the node for the key to add)
     * @param k  Key to add
     * @param val  Proper value to add to the given key (if null, nothing is to be added)
     * @param seen Recursion guard: if we see a node twice in the same recursion,
     *             there is a cycle
     */
    private void addSucc(final @NonNull PSet<LNode> pred, final K k, final @Nullable V val, final PSet<K> seen) {
        if (seen.contains(k)) {
            throw new IllegalStateException("Cycle in graph generated by " + keyOrder);
        }

        LNode leaf = leaves.get(k);
        if (leaf != null) {
            leaf.addProperVal(val); // TODO needs to add this to all successor query nodes
            return;
        }

        { // keep the scope of n small, outside of this it would be null anyway
            QueryNode n = qNodes.get(k);
            if (n != null) { // already exists

                //                if (pred == null) { // TODO needs to add this to all successor query nodes
                    n.addProperVal(val); // propagate new val to all successors, only if it was pruned
                //                }
                link(pred, n); // make sure the predecessor is linked
                return;
            }
        }

        if (queryKeySelector.test(k)) { // needs a new query node
            // (3)
            QueryNode n = new QueryNode(k);
            n.addProperVal(val);
            qNodes.put(k, n);
            link(pred, n);

            PSet<LNode> newPreds = pred.plus(n);
            PSet<K> newSeen = seen.plus(k);
            keyOrder.directSuccessors(k)
                    .forEachRemaining(next -> addSucc(newPreds, next, val, newSeen));
        } else {
            final @NonNull PSet<LNode> pred2;
            if (pred.isEmpty()) {
                // This is a leaf (for now, there may be predecessors added later)
                LeafNode leafOfK = new LeafNode(k);
                leafOfK.addProperVal(val);
                pred2 = pred.plus(leafOfK);
                leaves.put(k, leafOfK);
            } else {
                pred2 = pred;
            }

            // Otherwise the node for this key is an inner node, but
            // since it cannot be queried, we'll directly link the
            // predecessor to the successors (skipping this key).
            // Eg
            // A -> B -> C -> D      (where none are query nodes)
            // Then when calling put(A, v) for the first time, we'll first recurse with
            //      addSucc(pred: null, _, key: A, val: v)
            // Then, seeing pred == null above, we create a leaf for A, leaf(A)

            // The second recursion (marker (1) below) will be
            //      addSucc(pred: leaf(A), _, key: B, val: v)
            // Seeing the key B is not a query node, but since pred == leaf(A) != null,
            // we don't have to create a node for B. The next recursion
            // is
            //      addSucc(pred: leaf(A), _, key: C, val: v)
            // So this is the edge A -> C (skipping B)
            // Same thing happens once more on C:
            //      addSucc(pred: leaf(A), _, key: D, val: v)
            // So this is the longer edge A -> D

            // At this point D has no more successors, so it's handled
            // at marker (2) below: we just link A to the blackhole.
            // Since any incoming value for A do not interest any known
            // query node, they'll just be ignored: the next call to put(A, v')
            // will get the blackhole node from the leaf map for A

            // If instead, eg C was queryable, then on the third recursion:
            //      addSucc(pred: leaf(A), _, key: C, val: v)
            // We would fall in the branch for marker (3) above. We'd
            // create a new query node for C, qnode(C), add an edge leaf(A) -> qnode(C),
            // and change pred in the next recursion:
            //      addSucc(pred: qnode(C), _, key: D, val: null)


            // (2)
            Iterator<K> successors = keyOrder.directSuccessors(k);
            if (!successors.hasNext()) {
                LNode onlyPred = CollectionUtil.asSingle(pred2);
                if (onlyPred != null) {
                    // delete the leaf (replaced by the sink)
                    leaves.put(onlyPred.key, blackHole);
                    return;
                }
                // otherwise fallthrough
            }

            // (1)
            PSet<K> nextSeen = seen.plus(k);
            successors.forEachRemaining(next -> addSucc(pred2, next, val, nextSeen));
        }
    }

    private void transitiveReduction() {

        // look for chains i -> j -> k, and delete i -> k if it exists
        // note, that this is not optimal at all, but since we do it only
        // upon construction it doesn't matter

        for (QueryNode j : qNodes.values()) {
            for (LNode i : j.preds) {
                if (!i.equals(j)) {
                    for (QueryNode k : j.succ) {
                        // i -> j -> k
                        if (!k.equals(j)) {
                            if (i.succ.contains(k)) {
                                // i -> k
                                i.succ = i.succ.minus(k);
                                k.preds = k.preds.minus(i);
                            }
                        }
                    }
                }
            }
        }

    }

    private void link(Set<LNode> preds, QueryNode succ) {
        if (succ == null) {
            return;
        }
        preds.forEach(pred -> pred.succ = pred.succ.plus(succ));
    }

    /**
     * Adds one value to the given key. This value will be joined to the
     * values of all keys inferior to it when calling {@link #get(Object)}.
     *
     * @throws IllegalStateException If the order has a cycle
     */
    public void put(K key, V value) {
        AssertionUtil.requireParamNotNull("key", key);
        state.ensureWritable();
        addSucc(HashTreePSet.empty(), key, value, HashTreePSet.empty());
    }

    /**
     * Returns the computed value for the given key, or an empty set.
     * Only keys matching the filter given when constructing the lattice
     * can be queried, if that is not the case, then this will return
     * the empty set even if some values were {@link #put(Object, Object)}
     * for it.
     */
    @NonNull
    public Set<V> get(K key) {
        AssertionUtil.requireParamNotNull("key", key);
        state.ensureReadable();
        QueryNode n = qNodes.get(key);
        return n == null ? HashTreePSet.empty() : n.computeValue();
    }

    void makeWritable() {
        state = State.WRITE_ALLOWED;
        // just invalidate
        for (LNode n : qNodes.values()) {
            n.invalidate();
        }
        for (LNode n : leaves.values()) {
            n.invalidate();
        }
    }

    void makeWritableAndClear() {
        state = State.WRITE_ALLOWED;
        // also resets proper values
        for (LNode n : qNodes.values()) {
            n.resetValue();
        }
        for (LNode n : leaves.values()) {
            n.resetValue();
        }
    }

    void makeReadable() {
        state = State.READ_ALLOWED;
    }


    @Override
    public String toString() {
        // generates a DOT representation of the lattice
        // Visualize eg at http://webgraphviz.com/
        return GraphUtils.toDot(
            allNodes(),
            n -> n.succ,
            n -> n.getClass() == QueryNode.class ? DotColor.GREEN : DotColor.BLACK,
            LNode::describe
        );
    }

    private Set<LNode> allNodes() {
        return CollectionUtil.union(qNodes.values(), leaves.values());
    }

    private enum State {
        WRITE_ALLOWED,
        READ_ALLOWED;

        void ensureWritable() {
            if (this != WRITE_ALLOWED) {
                throw new IllegalStateException("Lattice may not be mutated");
            }
        }

        void ensureReadable() {
            if (this != READ_ALLOWED) {
                throw new IllegalStateException("Lattice is not ready to be read");
            }
        }
    }

    private abstract class LNode { // "Lattice Node"

        // note the key is non-null except in BlackHoleNode,
        // so it must override all this.
        protected final @NonNull K key;
        /** Proper value associated with this node (independent of topology). */
        protected @NonNull Set<V> properVal = new LinkedHashSet<>();
        PSet<QueryNode> succ = HashTreePSet.empty();

        private LNode(@NonNull K key) {
            this.key = key;
        }

        /**
         * Add a value to the node. This will be combined with the values
         * of successors. The blackhole node ignores the value, because
         * it will never be queried, so we save space.
         */
        void addProperVal(V v) {
            if (v == null) {
                return;
            }
            properVal.add(v);
        }

        /**
         * Invalidate the *computed* value, because the topology might
         * change. The proper value is not touched.
         */
        protected void invalidate() {
            // to be overridden
        }

        /**
         * Compute the value of this node by accumulating it with its
         * successors. Only query nodes do this.
         */
        Set<V> computeValue() {
            return properVal;
        }

        /** Reset the proper value (and the combined value). */
        protected void resetValue() {
            properVal = new LinkedHashSet<>();
        }

        /** Describe the key. */
        protected String describe() {
            return keyToString.apply(key);
        }

    }

    /**
     * A node that may be queried with {@link #get(Object)}.
     */
    private final class QueryNode extends LNode {

        PSet<LNode> preds = HashTreePSet.empty();

        private LinkedHashSet<V> value;

        /** Cached value */
        private @Nullable PSet<V> combinedVal;
        private boolean isValueUpToDate = false;

        QueryNode(@NonNull K key) {
            super(key);
        }

        @Override
        protected void invalidate() {
            super.invalidate();
            isValueUpToDate = false;
        }

        @Override
        PSet<V> computeValue() {
            if (combinedVal != null && isValueUpToDate) {
                return combinedVal;
            }

            PSet<V> value = reduceSuccessors(new HashSet<>());
            combinedVal = value;
            isValueUpToDate = true;
            return value;
        }

        /**
         * Recurses on the successors. Uses the parameter to avoid
         * visiting the same successor twice (this may happen if we
         * have a diamond situation in the lattice). Since this computes
         * the value recursively, some values may be set on the visited
         * successors as up-to-date (but this requires that all transitive
         * successors of a node are visited, which may not be the case
         * if we have a diamond).
         */
        private PSet<V> reduceSuccessors(Set<LNode> seen) {
            if (combinedVal != null && isValueUpToDate) {
                return combinedVal;
            }

            isValueUpToDate = true;

            PSet<V> val = HashTreePSet.from(properVal);

            for (LNode child : preds) {
                if (seen.add(child)) {
                    if (child.getClass() == getClass()) { // illegal to cast to generic type
                        val = val.plusAll(((QueryNode) child).reduceSuccessors(seen));
                        isValueUpToDate &= ((QueryNode) child).isValueUpToDate;
                    } else {
                        // leaf
                        val = val.plusAll(child.computeValue());
                    }
                } else {
                    // some predecessor was already visited, in which
                    // case its value is reachable from several paths.
                    isValueUpToDate = false;
                }
            }

            if (isValueUpToDate) {
                this.combinedVal = val;
            }

            return val;
        }

        @Override
        protected void resetValue() {
            super.resetValue();
            combinedVal = null;
            isValueUpToDate = false;
        }

        @Override
        public String toString() {
            return "node(" + key + ')';
        }
    }

    private final class LeafNode extends LNode {

        LeafNode(@NonNull K key) {
            super(key);
        }

        @Override
        public String toString() {
            return "leaf(" + key + ')';
        }
    }

    private final class BlackHoleNode extends LNode {

        BlackHoleNode() {
            super(null);
        }

        @Override
        protected String describe() {
            return "<blackhole>";
        }

        @Override
        void addProperVal(V v) {
            // do nothing
        }

        @Override
        protected void resetValue() {
            // do nothing
        }

        @Override
        public String toString() {
            return "<blackHole>";
        }
    }

}
