/*
 * Copyright 2015 U.S. Department of Veterans Affairs.
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
package gov.vha.isaac.csiro.classify;

import gov.vha.isaac.cradle.taxonomy.CradleTaxonomyProvider;
import gov.vha.isaac.cradle.taxonomy.graph.GraphCollector;
import gov.vha.isaac.metadata.coordinates.LogicCoordinates;
import gov.vha.isaac.metadata.coordinates.ViewCoordinates;
import gov.vha.isaac.ochre.api.DataSource;
import gov.vha.isaac.ochre.api.IdentifierService;
import gov.vha.isaac.ochre.api.LookupService;
import gov.vha.isaac.ochre.api.TaxonomyService;
import gov.vha.isaac.ochre.api.chronicle.LatestVersion;
import gov.vha.isaac.ochre.api.classifier.ClassifierResults;
import gov.vha.isaac.ochre.api.classifier.ClassifierService;
import gov.vha.isaac.ochre.api.commit.CommitService;
import gov.vha.isaac.ochre.api.component.concept.ConceptService;
import gov.vha.isaac.ochre.api.component.sememe.SememeService;
import gov.vha.isaac.ochre.api.component.sememe.SememeSnapshotService;
import gov.vha.isaac.ochre.api.coordinate.EditCoordinate;
import gov.vha.isaac.ochre.api.coordinate.LogicCoordinate;
import gov.vha.isaac.ochre.api.coordinate.StampCoordinate;
import gov.vha.isaac.ochre.api.logic.LogicalExpression;
import gov.vha.isaac.ochre.api.logic.Node;
import gov.vha.isaac.ochre.api.tree.TreeNodeVisitData;
import gov.vha.isaac.ochre.api.tree.hashtree.HashTreeBuilder;
import gov.vha.isaac.ochre.api.tree.hashtree.HashTreeWithBitSets;
import gov.vha.isaac.ochre.collections.ConceptSequenceSet;
import gov.vha.isaac.ochre.model.logic.LogicalExpressionOchreImpl;
import gov.vha.isaac.ochre.model.sememe.SememeChronologyImpl;
import gov.vha.isaac.ochre.model.sememe.version.LogicGraphSememeImpl;
import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import javafx.concurrent.Task;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.ihtsdo.otf.tcc.model.cc.concept.ConceptChronicle;
import org.ihtsdo.otf.tcc.model.version.Stamp;

/**
 *
 * @author kec
 */
public class ClassifierProvider implements ClassifierService {

    private static boolean VERBOSE = false;

    private static final Logger log = LogManager.getLogger();
    private static IdentifierService identifierService;
    private static TaxonomyService taxonomyService;
    private static SememeService sememeService;
    private static CommitService commitService;
    private static ConceptService conceptService;

    public static ConceptService getConceptService() {
        if (conceptService == null) {
            conceptService = LookupService.getService(ConceptService.class);
        }
        return conceptService;
    }

    public static CommitService getCommitService() {
        if (commitService == null) {
            commitService = LookupService.getService(CommitService.class);
        }
        return commitService;
    }

    public static IdentifierService getIdentifierService() {
        if (identifierService == null) {
            identifierService = LookupService.getService(IdentifierService.class);
        }
        return identifierService;
    }

    /**
     * @return the taxonomyService
     */
    public static TaxonomyService getTaxonomyService() {
        if (taxonomyService == null) {
            taxonomyService = LookupService.getService(TaxonomyService.class);
        }
        return taxonomyService;
    }

    public static SememeService getSememeService() {
        if (sememeService == null) {
            sememeService = LookupService.getService(SememeService.class);
        }
        return sememeService;
    }

    private final ClassifierChangeListener classifierChangeListener; // strong reference to prevent garbage collection
    StampCoordinate stampCoordinate;
    LogicCoordinate logicCoordinate;
    EditCoordinate editCoordinate;

    public ClassifierProvider(StampCoordinate stampCoordinate,
            LogicCoordinate logicCoordinate,
            EditCoordinate editCoordinate) {
        this.stampCoordinate = stampCoordinate;
        this.logicCoordinate = logicCoordinate;
        this.editCoordinate = editCoordinate;
        classifierChangeListener = new ClassifierChangeListener(
                LogicCoordinates.getStandardElProfile(), this);
        getCommitService().addChangeListener(classifierChangeListener);
    }

    public ConceptSequenceSet getNewConcepts() {
        return classifierChangeListener.getNewConcepts();
    }

    public boolean incrementalAllowed() {
        return classifierChangeListener.incrementalAllowed();
    }

    public void classifyComplete() {
        classifierChangeListener.classifyComplete();
    }

    @Override
    public Task<ClassifierResults> classify() {
        ClassifyTask task = ClassifyTask.create(this);
        return task;
    }

    public void printIfMoreNodes(int graphNodeCount, AtomicInteger maxGraphSize,
            ConceptChronicle conceptChronicle, LogicalExpressionOchreImpl logicGraph) {
        if (graphNodeCount > maxGraphSize.get()) {
            StringBuilder builder = new StringBuilder();
            printGraph(builder, "Make dl graph for: ", conceptChronicle, maxGraphSize, graphNodeCount, logicGraph);
            System.out.println(builder.toString());
        }
    }

    public void printIfMoreRevisions(SememeChronologyImpl<LogicGraphSememeImpl> logicGraphMember,
            AtomicInteger maxGraphVersionsPerMember, ConceptChronicle conceptChronicle, AtomicInteger maxGraphSize) {
        if (logicGraphMember.getVersionList() != null) {
            Collection<LogicGraphSememeImpl> versions = (Collection<LogicGraphSememeImpl>) logicGraphMember.getVersionList();
            int versionCount = versions.size();
            if (versionCount > maxGraphVersionsPerMember.get()) {
                maxGraphVersionsPerMember.set(versionCount);
                StringBuilder builder = new StringBuilder();
                builder.append("Encountered logic definition with ").append(versionCount).append(" versions:\n\n");
                int version = 0;
                LogicalExpressionOchreImpl previousVersion = null;
                for (LogicGraphSememeImpl lgmv : versions) {
                    LogicalExpressionOchreImpl lg = new LogicalExpressionOchreImpl(lgmv.getGraphData(), DataSource.INTERNAL,
                            getIdentifierService().getConceptSequence(logicGraphMember.getReferencedComponentNid()));
                    printGraph(builder, "Version " + version++ + " stamp: " + Stamp.stampFromIntStamp(lgmv.getStampSequence()).toString() + "\n ",
                            conceptChronicle, maxGraphSize, lg.getNodeCount(), lg);
                    if (previousVersion != null) {
                        int[] solution1 = lg.maximalCommonSubgraph(previousVersion);
                        int[] solution2 = previousVersion.maximalCommonSubgraph(lg);
                        builder.append("Solution this to previous: [");
                        for (int i = 0; i < solution1.length; i++) {
                            if (solution1[i] != -1) {
                                builder.append("(");
                                builder.append(i);
                                builder.append("->");
                                builder.append(solution1[i]);
                                builder.append(")");
                            }
                        }
                        builder.append("]\nSolution previous to this: [");
                        for (int i = 0; i < solution2.length; i++) {
                            if (solution2[i] != -1) {
                                builder.append("(");
                                builder.append(i);
                                builder.append("<-");
                                builder.append(solution2[i]);
                                builder.append(")");
                            }
                        }
                        builder.append("]\n");
                    }
                    previousVersion = lg;
                }
                System.out.println(builder.toString());

            }
        }
    }

    protected HashTreeWithBitSets getStatedTaxonomyGraph() {
        try {
            IntStream conceptSequenceStream = getIdentifierService().getParallelConceptSequenceStream();
            GraphCollector collector = new GraphCollector(((CradleTaxonomyProvider) getTaxonomyService()).getOriginDestinationTaxonomyRecords(),
                    ViewCoordinates.getDevelopmentStatedLatestActiveOnly());
            HashTreeBuilder graphBuilder = conceptSequenceStream.collect(
                    HashTreeBuilder::new,
                    collector,
                    collector);
            HashTreeWithBitSets resultGraph = graphBuilder.getSimpleDirectedGraphGraph();
            return resultGraph;
        } catch (IOException ex) {
            throw new RuntimeException(ex);
        }
    }

    protected HashTreeWithBitSets getInferredTaxonomyGraph() {
        IntStream conceptSequenceStream = getIdentifierService().getParallelConceptSequenceStream();
        GraphCollector collector = new GraphCollector(((CradleTaxonomyProvider) getTaxonomyService()).getOriginDestinationTaxonomyRecords(),
                ViewCoordinates.getDevelopmentInferredLatestActiveOnly());
        HashTreeBuilder graphBuilder = conceptSequenceStream.collect(
                HashTreeBuilder::new,
                collector,
                collector);
        HashTreeWithBitSets resultGraph = graphBuilder.getSimpleDirectedGraphGraph();
        return resultGraph;
    }

    private void printGraph(StringBuilder builder, String prefix, ConceptChronicle chronicle,
            AtomicInteger maxGraphSize, int graphNodeCount,
            LogicalExpressionOchreImpl logicGraph) {
        builder.append(prefix).append(chronicle.toString());
        builder.append("\n uuid: ");
        builder.append(chronicle.getPrimordialUuid());
        builder.append("\nnodes: ");
        builder.append(logicGraph.getNodeCount());
        builder.append("\n");
        maxGraphSize.set(Math.max(graphNodeCount, maxGraphSize.get()));
        logicGraph.processDepthFirst((Node node, TreeNodeVisitData graphVisitData) -> {
            for (int i = 0; i < graphVisitData.getDistance(node.getNodeIndex()); i++) {
                builder.append("    ");
            }
            builder.append(node);
            builder.append("\n");
        });
        builder.append(" \n\n");
    }

    @Override
    public Optional<LatestVersion<? extends LogicalExpression>> getLogicalExpression(int conceptId, int logicAssemblageId,
            StampCoordinate stampCoordinate) {
        SememeSnapshotService<LogicGraphSememeImpl> ssp
                = getSememeService().getSnapshot(LogicGraphSememeImpl.class, stampCoordinate);

        List<LatestVersion<LogicalExpressionOchreImpl>> latestVersions
                = ssp.getLatestSememeVersionsForComponentFromAssemblage(
                        conceptId, logicAssemblageId).map((LatestVersion<LogicGraphSememeImpl> lgs) -> {
                            LogicalExpressionOchreImpl expressionValue
                            = new LogicalExpressionOchreImpl(lgs.value().getGraphData(), DataSource.INTERNAL, lgs.value().getReferencedComponentNid());
                            LatestVersion<LogicalExpressionOchreImpl> latestExpressionValue = new LatestVersion<>(expressionValue);

                            if (lgs.contradictions().isPresent()) {
                                lgs.contradictions().get().forEach((LogicGraphSememeImpl contradiction) -> {
                                    LogicalExpressionOchreImpl contradictionValue
                                    = new LogicalExpressionOchreImpl(contradiction.getGraphData(), DataSource.INTERNAL, contradiction.getReferencedComponentNid());
                                    latestExpressionValue.addLatest(contradictionValue);
                                });
                            }

                            return latestExpressionValue;
                        }).collect(Collectors.toList());

        if (latestVersions.isEmpty()) {
            return Optional.empty();
        }

        if (latestVersions.size() > 1) {
            throw new IllegalStateException("More than one LogicGraphSememeImpl for concept in assemblage: "
                    + latestVersions);
        }
        return Optional.of(latestVersions.get(0));
    }

    @Override
    public Task<Integer> getConceptSequenceForExpression(LogicalExpression expression,
            EditCoordinate editCoordinate) {
        return GetConceptSequenceForExpressionTask.create(expression, this, editCoordinate);
    }

}