/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package gov.vha.isaac.logic.node.external;

import gov.vha.isaac.logic.LogicGraph;
import gov.vha.isaac.logic.NodeSemantic;
import gov.vha.isaac.logic.node.AbstractNode;
import gov.vha.isaac.logic.node.internal.RoleNodeAllWithNids;
import gov.vha.isaac.ochre.api.DataTarget;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.IOException;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import org.ihtsdo.otf.tcc.api.uuid.UuidT5Generator;

/**
 *
 * @author kec
 */
public class RoleNodeAllWithUuids extends TypedNodeWithUuids {

    public RoleNodeAllWithUuids(LogicGraph logicGraphVersion, DataInputStream dataInputStream) throws IOException {
        super(logicGraphVersion, dataInputStream);
    }

    public RoleNodeAllWithUuids(LogicGraph logicGraphVersion, UUID typeConceptUuid, AbstractNode child) {
        super(logicGraphVersion, typeConceptUuid, child);
    }

    public RoleNodeAllWithUuids(RoleNodeAllWithNids internalFrom) {
        super(internalFrom);
    }

    @Override
    public void writeNodeData(DataOutput dataOutput, DataTarget dataTarget) throws IOException {
        super.writeData(dataOutput, dataTarget);
    }


    @Override
    public NodeSemantic getNodeSemantic() {
        return NodeSemantic.ROLE_ALL;
    }
    
    @Override
    protected UUID initNodeUuid() {
        
            try {
                return UuidT5Generator.get(getNodeSemantic().getSemanticUuid(), 
                        typeConceptUuid.toString());
            } catch (IOException| NoSuchAlgorithmException ex) {
                throw new RuntimeException(ex);
            } 
        
     }

    @Override
    public String toString() {
        return "RoleNodeAll[" + getNodeIndex() + "]:" + super.toString();
    }
}
