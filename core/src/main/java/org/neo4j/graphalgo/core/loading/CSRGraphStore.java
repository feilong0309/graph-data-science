/*
 * Copyright (c) 2017-2020 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.graphalgo.core.loading;

import org.immutables.builder.Builder.AccessibleFields;
import org.neo4j.graphalgo.NodeLabel;
import org.neo4j.graphalgo.RelationshipType;
import org.neo4j.graphalgo.annotation.ValueClass;
import org.neo4j.graphalgo.api.Graph;
import org.neo4j.graphalgo.api.GraphStore;
import org.neo4j.graphalgo.api.NodeMapping;
import org.neo4j.graphalgo.api.NodeProperties;
import org.neo4j.graphalgo.api.UnionNodeProperties;
import org.neo4j.graphalgo.api.schema.GraphStoreSchema;
import org.neo4j.graphalgo.api.schema.NodeSchema;
import org.neo4j.graphalgo.api.schema.RelationshipSchema;
import org.neo4j.graphalgo.core.ProcedureConstants;
import org.neo4j.graphalgo.core.huge.HugeGraph;
import org.neo4j.graphalgo.core.huge.NodeFilteredGraph;
import org.neo4j.graphalgo.core.huge.UnionGraph;
import org.neo4j.graphalgo.core.utils.TimeUtil;
import org.neo4j.graphalgo.core.utils.paged.AllocationTracker;
import org.neo4j.graphalgo.utils.StringJoining;
import org.neo4j.values.storable.NumberType;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.Collections.singletonList;
import static java.util.Collections.singletonMap;
import static java.util.stream.Collectors.toMap;
import static org.neo4j.graphalgo.NodeLabel.ALL_NODES;
import static org.neo4j.graphalgo.utils.StringFormatting.formatWithLocale;

public final class CSRGraphStore implements GraphStore {

    private final int concurrency;

    private final IdMap nodes;

    private final Map<NodeLabel, NodePropertyStore> nodeProperties;

    private final Map<RelationshipType, HugeGraph.TopologyCSR> relationships;

    private final Map<RelationshipType, RelationshipPropertyStore> relationshipProperties;

    private final Set<Graph> createdGraphs;

    private final AllocationTracker tracker;

    private ZonedDateTime modificationTime;

    public static GraphStore of(
        IdMap nodes,
        Map<NodeLabel, Map<String, NodeProperties>> nodeProperties,
        Map<RelationshipType, HugeGraph.TopologyCSR> relationships,
        Map<RelationshipType, Map<String, HugeGraph.PropertyCSR>> relationshipProperties,
        int concurrency,
        AllocationTracker tracker
    ) {
        Map<NodeLabel, NodePropertyStore> nodePropertyStores = new HashMap<>(nodeProperties.size());
        nodeProperties.forEach((nodeLabel, propertyMap) -> {
            NodePropertyStore.Builder builder = NodePropertyStore.builder();
            propertyMap.forEach((propertyKey, propertyValues) -> builder.putNodeProperty(
                propertyKey,
                NodeProperty.of(propertyKey, NumberType.FLOATING_POINT, PropertyState.PERSISTENT, propertyValues)
            ));
            nodePropertyStores.put(nodeLabel, builder.build());
        });

        Map<RelationshipType, RelationshipPropertyStore> relationshipPropertyStores = new HashMap<>();
        relationshipProperties.forEach((relationshipType, propertyMap) -> {
            RelationshipPropertyStore.Builder builder = RelationshipPropertyStore.builder();
            propertyMap.forEach((propertyKey, propertyValues) -> builder.putRelationshipProperty(
                propertyKey,
                RelationshipProperty.of(propertyKey, NumberType.FLOATING_POINT, PropertyState.PERSISTENT, propertyValues)
            ));
            relationshipPropertyStores.put(relationshipType, builder.build());
        });

        return new CSRGraphStore(
            nodes,
            nodePropertyStores,
            relationships,
            relationshipPropertyStores,
            concurrency,
            tracker
        );
    }

    public static GraphStore of(
        HugeGraph graph,
        String relationshipType,
        Optional<String> relationshipProperty,
        int concurrency,
        AllocationTracker tracker
    ) {
        HugeGraph.Relationships relationships = graph.relationships();

        Map<RelationshipType, HugeGraph.TopologyCSR> topology = singletonMap(RelationshipType.of(relationshipType), relationships.topology());

        Map<NodeLabel, Map<String, NodeProperties>> nodeProperties = new HashMap<>();
        nodeProperties.put(
            ALL_NODES,
            graph.availableNodeProperties().stream().collect(toMap(
                Function.identity(),
                graph::nodeProperties
            ))
        );

        Map<RelationshipType, Map<String, HugeGraph.PropertyCSR>> relationshipProperties = Collections.emptyMap();
        if (relationshipProperty.isPresent() && relationships.properties().isPresent()) {
            relationshipProperties = singletonMap(
                RelationshipType.of(relationshipType),
                singletonMap(relationshipProperty.get(), relationships.properties().get())
            );
        }

        return CSRGraphStore.of(graph.idMap(), nodeProperties, topology, relationshipProperties, concurrency, tracker);
    }

    private CSRGraphStore(
        IdMap nodes,
        Map<NodeLabel, NodePropertyStore> nodeProperties,
        Map<RelationshipType, HugeGraph.TopologyCSR> relationships,
        Map<RelationshipType, RelationshipPropertyStore> relationshipProperties,
        int concurrency,
        AllocationTracker tracker
    ) {
        this.nodes = nodes;
        this.nodeProperties = nodeProperties;
        this.relationships = relationships;
        this.relationshipProperties = relationshipProperties;
        this.concurrency = concurrency;
        this.createdGraphs = new HashSet<>();
        this.modificationTime = TimeUtil.now();
        this.tracker = tracker;
    }

    @Override
    public GraphStoreSchema schema() {
        return GraphStoreSchema.of(nodeSchema(), relationshipTypeSchema());
    }

    @Override
    public ZonedDateTime modificationTime() {
        return modificationTime;
    }

    @Override
    public NodeMapping nodes() {
        return this.nodes;
    }

    @Override
    public Set<NodeLabel> nodeLabels() {
        return nodes.availableNodeLabels();
    }

    @Override
    public Set<String> nodePropertyKeys(NodeLabel label) {
        return new HashSet<>(nodeProperties.getOrDefault(label, NodePropertyStore.empty()).keySet());
    }

    @Override
    public Map<NodeLabel, Set<String>> nodePropertyKeys() {
        return nodeLabels().stream().collect(Collectors.toMap(Function.identity(), this::nodePropertyKeys));
    }

    @Override
    public long nodePropertyCount() {
        // TODO: This is not the correct value. We would need to look into the bitsets in order to retrieve the correct value.
        return nodeProperties.values().stream()
                   .mapToLong(nodePropertyStore -> nodePropertyStore.keySet().size())
                   .sum() * nodeCount();
    }

    @Override
    public boolean hasNodeProperty(Collection<NodeLabel> labels, String propertyKey) {
        return labels
            .stream()
            .allMatch(label -> nodeProperties.containsKey(label) && nodeProperties.get(label).containsKey(propertyKey));
    }

    @Override
    public void addNodeProperty(
        NodeLabel nodeLabel,
        String propertyKey,
        NumberType propertyType,
        NodeProperties propertyValues
    ) {
        if (!nodeLabels().contains(nodeLabel)) {
            throw new IllegalArgumentException(formatWithLocale(
                "Adding '%s.%s' to the graph store failed. Node label '%s' does not exist in the store. Available node labels: %s",
                nodeLabel.name, propertyKey, nodeLabel.name,
                StringJoining.join(nodeLabels().stream().map(NodeLabel::name))
            ));
        }
        updateGraphStore((graphStore) -> graphStore.nodeProperties.compute(nodeLabel, (k, nodePropertyStore) -> {
            NodePropertyStore.Builder storeBuilder = NodePropertyStore.builder();
            if (nodePropertyStore != null) {
                storeBuilder.from(nodePropertyStore);
            }
            return storeBuilder
                .putIfAbsent(propertyKey, NodeProperty.of(propertyKey, propertyType, PropertyState.TRANSIENT, propertyValues))
                .build();
        }));
    }

    @Override
    public void removeNodeProperty(NodeLabel nodeLabel, String propertyKey) {
        updateGraphStore(graphStore -> {
            if (graphStore.nodeProperties.containsKey(nodeLabel)) {
                NodePropertyStore updatedNodePropertyStore = NodePropertyStore.builder()
                    .from(graphStore.nodeProperties.get(nodeLabel))
                    .removeProperty(propertyKey)
                    .build();

                if (updatedNodePropertyStore.isEmpty()) {
                    graphStore.nodeProperties.remove(nodeLabel);
                } else {
                    graphStore.nodeProperties.replace(nodeLabel, updatedNodePropertyStore);
                }
            }
        });
    }

    @Override
    public NumberType nodePropertyType(NodeLabel label, String propertyKey) {
        return nodeProperty(label, propertyKey).type();
    }

    @Override
    public PropertyState nodePropertyState(String propertyKey) {
        return nodeProperty(propertyKey).state();
    }

    @Override
    public NodeProperties nodePropertyValues(String propertyKey) {
        return nodeProperty(propertyKey).values();
    }

    @Override
    public NodeProperties nodePropertyValues(NodeLabel label, String propertyKey) {
        return nodeProperty(label, propertyKey).values();
    }

    @Override
    public Set<RelationshipType> relationshipTypes() {
        return relationships.keySet();
    }

    @Override
    public boolean hasRelationshipType(RelationshipType relationshipType) {
        return relationships.containsKey(relationshipType);
    }

    @Override
    public long relationshipCount() {
        return relationships.values().stream()
            .mapToLong(HugeGraph.TopologyCSR::elementCount)
            .sum();
    }

    @Override
    public long relationshipCount(RelationshipType relationshipType) {
        return relationships.get(relationshipType).elementCount();
    }

    @Override
    public boolean hasRelationshipProperty(Collection<RelationshipType> relTypes, String propertyKey) {
        return relTypes
            .stream()
            .allMatch(relType -> relationshipProperties.containsKey(relType) && relationshipProperties.get(relType).containsKey(propertyKey));
    }

    @Override
    public NumberType relationshipPropertyType(String propertyKey) {
        return relationshipProperties.values().stream()
            .filter(propertyStore -> propertyStore.containsKey(propertyKey))
            .map(propertyStore -> propertyStore.get(propertyKey).type())
            .findFirst()
            .orElse(NumberType.NO_NUMBER);
    }

    @Override
    public long relationshipPropertyCount() {
        return relationshipProperties
            .values()
            .stream()
            .flatMapToLong(relationshipPropertyStore -> relationshipPropertyStore
                .values()
                .stream()
                .map(RelationshipProperty::values)
                .mapToLong(HugeGraph.PropertyCSR::elementCount))
            .sum();
    }

    @Override
    public Set<String> relationshipPropertyKeys() {
        return relationshipProperties
            .values()
            .stream()
            .flatMap(relationshipPropertyStore -> relationshipPropertyStore.keySet().stream())
            .collect(Collectors.toSet());
    }

    @Override
    public Set<String> relationshipPropertyKeys(RelationshipType relationshipType) {
        return relationshipProperties.getOrDefault(relationshipType, RelationshipPropertyStore.empty()).keySet();
    }

    @Override
    public void addRelationshipType(
        RelationshipType relationshipType,
        Optional<String> relationshipPropertyKey,
        Optional<NumberType> relationshipPropertyType,
        HugeGraph.Relationships relationships
    ) {
        updateGraphStore(graphStore -> {
            if (!hasRelationshipType(relationshipType)) {
                graphStore.relationships.put(relationshipType, relationships.topology());

                if (relationshipPropertyKey.isPresent()
                    && relationshipPropertyType.isPresent()
                    && relationships.properties().isPresent()) {
                    addRelationshipProperty(
                        relationshipType,
                        relationshipPropertyKey.get(),
                        relationshipPropertyType.get(),
                        relationships.properties().get(),
                        graphStore
                    );
                }
            }
        });
    }

    @Override
    public DeletionResult deleteRelationships(RelationshipType relationshipType) {
        return DeletionResult.of(builder ->
            updateGraphStore(graphStore -> {
                builder.deletedRelationships(graphStore.relationships.get(relationshipType).elementCount());
                graphStore.relationshipProperties
                    .getOrDefault(relationshipType, RelationshipPropertyStore.empty())
                    .relationshipProperties().values().forEach(property -> {
                    builder.putDeletedProperty(property.key(), property.values().elementCount());
                });
                graphStore.relationships.remove(relationshipType);
                graphStore.relationshipProperties.remove(relationshipType);
            })
        );
    }

    @Override
    public Graph getGraph(
        Collection<NodeLabel> nodeLabels,
        Collection<RelationshipType> relationshipTypes,
        Optional<String> maybeRelationshipProperty
    ) {
        validateInput(relationshipTypes, maybeRelationshipProperty);
        return createGraph(nodeLabels, relationshipTypes, maybeRelationshipProperty);
    }

    @Override
    public Graph getUnion() {
        return UnionGraph.of(relationships
            .keySet()
            .stream()
            .flatMap(relationshipType -> {
                if (relationshipProperties.containsKey(relationshipType)) {
                    return relationshipProperties
                        .get(relationshipType)
                        .keySet()
                        .stream()
                        .map(propertyKey -> createGraph(nodeLabels(), relationshipType, Optional.of(propertyKey)));
                } else {
                    return Stream.of(createGraph(nodeLabels(), relationshipType, Optional.empty()));
                }
            })
            .collect(Collectors.toList()));
    }

    @Override
    public void canRelease(boolean canRelease) {
        createdGraphs.forEach(graph -> graph.canRelease(canRelease));
    }

    @Override
    public void release() {
        createdGraphs.forEach(Graph::release);
    }

    @Override
    public long nodeCount() {
        return nodes.nodeCount();
    }

    private synchronized void updateGraphStore(Consumer<CSRGraphStore> updateFunction) {
        updateFunction.accept(this);
        this.modificationTime = TimeUtil.now();
    }

    private NodeProperty nodeProperty(NodeLabel label, String propertyKey) {
        return this.nodeProperties.getOrDefault(label, NodePropertyStore.empty()).get(propertyKey);
    }

    private NodeProperty nodeProperty(String propertyKey) {
        if (nodes.availableNodeLabels().size() > 1) {
            var unionValues = new HashMap<NodeLabel, NodeProperties>();
            var unionType = NumberType.NO_NUMBER;
            var unionOrigin = PropertyState.PERSISTENT;

            for (var labelAndPropertyStore : nodeProperties.entrySet()) {
                var nodeLabel = labelAndPropertyStore.getKey();
                var nodePropertyStore = labelAndPropertyStore.getValue();
                if (nodePropertyStore.containsKey(propertyKey)) {
                    var nodeProperty = nodePropertyStore.get(propertyKey);
                    unionValues.put(nodeLabel, nodeProperty.values());
                    unionType = nodeProperty.type();
                    unionOrigin = nodeProperty.state();
                }
            }

            return NodeProperty.of(
                propertyKey,
                unionType,
                unionOrigin,
                new UnionNodeProperties(nodes, unionValues)
            );
        } else {
            return nodeProperties.get(nodes.availableNodeLabels().iterator().next()).get(propertyKey);
        }
    }

    private void addRelationshipProperty(
        RelationshipType relationshipType,
        String propertyKey,
        NumberType propertyType,
        HugeGraph.PropertyCSR propertyCSR,
        CSRGraphStore graphStore
    ) {
        graphStore.relationshipProperties.compute(relationshipType, (relType, propertyStore) -> {
            RelationshipPropertyStore.Builder builder = RelationshipPropertyStore.builder();
            if (propertyStore != null) {
                builder.from(propertyStore);
            }
            return builder.putIfAbsent(
                propertyKey,
                ImmutableRelationshipProperty.of(propertyKey, propertyType, PropertyState.TRANSIENT, propertyCSR)
            ).build();
        });
    }

    private Graph createGraph(
        Collection<NodeLabel> nodeLabels,
        RelationshipType relationshipType,
        Optional<String> maybeRelationshipProperty
    ) {
        return createGraph(nodeLabels, singletonList(relationshipType), maybeRelationshipProperty);
    }

    private Graph createGraph(
        Collection<NodeLabel> filteredLabels,
        Collection<RelationshipType> relationshipTypes,
        Optional<String> maybeRelationshipProperty
    ) {
        boolean loadAllNodes = filteredLabels.containsAll(nodeLabels());

        Optional<IdMap> filteredNodes = loadAllNodes || nodes.containsOnlyAllNodesLabel()
            ? Optional.empty()
            : Optional.of(nodes.withFilteredLabels(filteredLabels, concurrency));

        List<Graph> filteredGraphs = relationships.entrySet().stream()
            .filter(relTypeAndCSR -> relationshipTypes.contains(relTypeAndCSR.getKey()))
            .map(relTypeAndCSR -> {
                Map<String, NodeProperties> filteredNodeProperties = filterNodeProperties(filteredLabels);

                HugeGraph initialGraph = HugeGraph.create(
                    nodes,
                    filteredNodeProperties,
                    relTypeAndCSR.getValue(),
                    maybeRelationshipProperty.map(propertyKey -> relationshipProperties
                        .get(relTypeAndCSR.getKey())
                        .get(propertyKey).values()),
                    tracker
                );

                if (filteredNodes.isPresent()) {
                    return new NodeFilteredGraph(initialGraph, filteredNodes.get());
                } else {
                    return initialGraph;
                }
            })
            .collect(Collectors.toList());

        filteredGraphs.forEach(graph -> graph.canRelease(false));
        createdGraphs.addAll(filteredGraphs);
        return UnionGraph.of(filteredGraphs);
    }

    private Map<String, NodeProperties> filterNodeProperties(Collection<NodeLabel> labels) {
        if (this.nodeProperties.isEmpty()) {
            return Collections.emptyMap();
        }
        if (labels.size() == 1 || nodes.containsOnlyAllNodesLabel()) {
            return this.nodeProperties.getOrDefault(labels.iterator().next(), NodePropertyStore.empty()).nodePropertyValues();
        }

        Map<String, Map<NodeLabel, NodeProperties>> invertedNodeProperties = new HashMap<>();
        nodeProperties
            .entrySet()
            .stream()
            .filter(entry -> labels.contains(entry.getKey()) || labels.contains(ALL_NODES))
            .forEach(entry -> entry.getValue().nodeProperties()
                .forEach((propertyKey, nodeProperty) -> invertedNodeProperties
                    .computeIfAbsent(
                        propertyKey,
                        ignored -> new HashMap<>()
                    )
                    .put(entry.getKey(), nodeProperty.values())
                ));

        return invertedNodeProperties
            .entrySet()
            .stream()
            .collect(Collectors.toMap(
                Entry::getKey,
                entry -> new UnionNodeProperties(nodes, entry.getValue())
            ));
    }

    private void validateInput(Collection<RelationshipType> relationshipTypes, Optional<String> maybeRelationshipProperty) {
        if (relationshipTypes.isEmpty()) {
            throw new IllegalArgumentException(formatWithLocale(
                "The parameter '%s' should not be empty. Use '*' to load all relationship types.",
                ProcedureConstants.RELATIONSHIP_TYPES
            ));
        }

        relationshipTypes.forEach(relationshipType -> {
            if (!relationships.containsKey(relationshipType)) {
                throw new IllegalArgumentException(formatWithLocale(
                    "No relationships have been loaded for relationship type '%s'",
                    relationshipType
                ));
            }

            maybeRelationshipProperty.ifPresent(relationshipProperty -> {
                if (!hasRelationshipProperty(singletonList(relationshipType), relationshipProperty)) {
                    throw new IllegalArgumentException(formatWithLocale(
                        "Property '%s' does not exist for relationships with type '%s'.",
                        maybeRelationshipProperty.get(),
                        relationshipType
                    ));
                }
            });
        });
    }

    private NodeSchema nodeSchema() {
        NodeSchema.Builder nodePropsBuilder = NodeSchema.builder();

        nodeProperties.forEach((label, propertyStore) ->
            propertyStore.nodeProperties().forEach((propertyName, nodeProperty) -> {
                nodePropsBuilder.addPropertyAndTypeForLabel(label, propertyName, nodeProperty.type());
            }));

        for (NodeLabel nodeLabel : nodeLabels()) {
            nodePropsBuilder.addEmptyMapForLabelWithoutProperties(nodeLabel);
        }
        return nodePropsBuilder.build();
    }

    private RelationshipSchema relationshipTypeSchema() {
        RelationshipSchema.Builder relationshipPropsBuilder = RelationshipSchema.builder();

        relationshipProperties.forEach((type, propertyStore) -> {
            propertyStore.relationshipProperties().forEach((propertyName, relationshipProperty) -> {
                relationshipPropsBuilder.addPropertyAndTypeForRelationshipType(
                    type,
                    propertyName,
                    relationshipProperty.type()
                );
            });
        });

        for (RelationshipType type : relationshipTypes()) {
            relationshipPropsBuilder.addEmptyMapForRelationshipTypeWithoutProperties(type);
        }
        return relationshipPropsBuilder.build();
    }

    @ValueClass
    interface NodeProperty {

        String key();

        NumberType type();

        PropertyState state();

        NodeProperties values();

        static NodeProperty of(String key, NumberType type, PropertyState origin, NodeProperties values) {
            return ImmutableNodeProperty.of(key, type, origin, values);
        }
    }

    @ValueClass
    public interface NodePropertyStore {

        Map<String, NodeProperty> nodeProperties();

        default Map<String, NodeProperties> nodePropertyValues() {
            return nodeProperties()
                .entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().values()));
        }

        default NodeProperty get(String propertyKey) {
            return nodeProperties().get(propertyKey);
        }

        default boolean isEmpty() {
            return nodeProperties().isEmpty();
        }

        default Set<String> keySet() {
            return nodeProperties().keySet();
        }

        default boolean containsKey(String propertyKey) {
            return nodeProperties().containsKey(propertyKey);
        }

        static NodePropertyStore empty() {
            return ImmutableNodePropertyStore.of(Collections.emptyMap());
        }

        static Builder builder() {
            // need to initialize with empty map due to `deferCollectionAllocation = true`
            return new Builder().nodeProperties(Collections.emptyMap());
        }

        @AccessibleFields
        final class Builder extends ImmutableNodePropertyStore.Builder {

            Builder putIfAbsent(String propertyKey, NodeProperty nodeProperty) {
                nodeProperties.putIfAbsent(propertyKey, nodeProperty);
                return this;
            }

            Builder removeProperty(String propertyKey) {
                nodeProperties.remove(propertyKey);
                return this;
            }
        }
    }

    @ValueClass
    public interface RelationshipPropertyStore {

        Map<String, RelationshipProperty> relationshipProperties();

        default RelationshipProperty get(String propertyKey) {
            return relationshipProperties().get(propertyKey);
        }

        default boolean isEmpty() {
            return relationshipProperties().isEmpty();
        }

        default Set<String> keySet() {
            return relationshipProperties().keySet();
        }

        default Collection<RelationshipProperty> values() {
            return relationshipProperties().values();
        }

        default boolean containsKey(String propertyKey) {
            return relationshipProperties().containsKey(propertyKey);
        }

        static RelationshipPropertyStore empty() {
            return ImmutableRelationshipPropertyStore.of(Collections.emptyMap());
        }

        static Builder builder() {
            // need to initialize with empty map due to `deferCollectionAllocation = true`
            return new Builder().relationshipProperties(Collections.emptyMap());
        }

        @AccessibleFields
        final class Builder extends ImmutableRelationshipPropertyStore.Builder {

            Builder putIfAbsent(String propertyKey, RelationshipProperty relationshipProperty) {
                relationshipProperties.putIfAbsent(propertyKey, relationshipProperty);
                return this;
            }
        }

    }

    @ValueClass
    public interface RelationshipProperty {

        String key();

        NumberType type();

        PropertyState state();

        HugeGraph.PropertyCSR values();

        static RelationshipProperty of(String key, NumberType type, PropertyState state, HugeGraph.PropertyCSR values) {
            return ImmutableRelationshipProperty.of(key, type, state, values);
        }
    }
}



