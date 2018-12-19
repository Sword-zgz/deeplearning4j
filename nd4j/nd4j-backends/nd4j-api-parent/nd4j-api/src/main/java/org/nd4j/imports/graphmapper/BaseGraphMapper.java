/*******************************************************************************
 * Copyright (c) 2015-2018 Skymind, Inc.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Apache License, Version 2.0 which is available at
 * https://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * SPDX-License-Identifier: Apache-2.0
 ******************************************************************************/

package org.nd4j.imports.graphmapper;

import com.github.os72.protobuf351.Message;
import com.github.os72.protobuf351.TextFormat;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.builder.Diff;
import org.nd4j.autodiff.functions.DifferentialFunction;
import org.nd4j.autodiff.samediff.SDVariable;
import org.nd4j.autodiff.samediff.SameDiff;
import org.nd4j.autodiff.samediff.VariableType;
import org.nd4j.autodiff.samediff.internal.SameDiffOp;
import org.nd4j.autodiff.samediff.internal.Variable;
import org.nd4j.base.Preconditions;
import org.nd4j.imports.NoOpNameFoundException;
import org.nd4j.imports.descriptors.properties.PropertyMapping;
import org.nd4j.linalg.api.buffer.DataBuffer;
import org.nd4j.linalg.api.buffer.DataType;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.nd4j.linalg.api.ops.Op;
import org.nd4j.linalg.api.shape.LongShapeDescriptor;
import org.nd4j.linalg.exception.ND4JIllegalStateException;
import org.nd4j.linalg.factory.Nd4j;
import org.nd4j.weightinit.impl.ZeroInitScheme;

import java.io.*;
import java.lang.reflect.Field;
import java.util.*;

/**
 * Base implementation for importing a graph
 *
 * @param <GRAPH_TYPE> the type of graph
 * @param <NODE_TYPE>  the type of node
 * @param <ATTR_TYPE>  the attribute type
 */
@Slf4j
public abstract class BaseGraphMapper<GRAPH_TYPE, NODE_TYPE, ATTR_TYPE, TENSOR_TYPE> implements GraphMapper<GRAPH_TYPE, NODE_TYPE, ATTR_TYPE, TENSOR_TYPE> {


    @Override
    public Op.Type opTypeForNode(NODE_TYPE nodeDef) {
        DifferentialFunction opWithTensorflowName = getMappedOp(getOpType(nodeDef));
        if (opWithTensorflowName == null)
            throw new NoOpNameFoundException("No op found with name " + getOpType(nodeDef));
        Op.Type type = opWithTensorflowName.opType();
        return type;

    }


    @Override
    public void mapProperties(DifferentialFunction on, NODE_TYPE node, GRAPH_TYPE graph, SameDiff sameDiff, Map<String, Map<String, PropertyMapping>> propertyMappings) {
        val mappings = propertyMappings.get(getOpType(node));
        if (mappings == null || mappings.isEmpty()) {
            return;
        }


        for (val entry : mappings.entrySet()) {
            mapProperty(entry.getKey(), on, node, graph, sameDiff, propertyMappings);
        }
    }


    /**
     * @param inputStream
     * @return
     */
    @Override
    public SameDiff importGraph(InputStream inputStream) {
        GRAPH_TYPE def = readGraph(inputStream);
        return importGraph(def);
    }

    protected GRAPH_TYPE readGraph(InputStream inputStream) {
        byte[] bytes = null;
        GRAPH_TYPE def = null;
        try {
            bytes = IOUtils.toByteArray(inputStream);
            def = parseGraphFrom(bytes);
        } catch (IOException e) {
            try (BufferedInputStream bis2 = new BufferedInputStream(new ByteArrayInputStream(bytes)); BufferedReader reader = new BufferedReader(new InputStreamReader(bis2))) {
                Message.Builder builder = getNewGraphBuilder();

                StringBuilder str = new StringBuilder();
                String line = null;
                while ((line = reader.readLine()) != null) {
                    str.append(line);//.append("\n");
                }

                TextFormat.getParser().merge(str.toString(), builder);
                def = (GRAPH_TYPE) builder.build();
            } catch (Exception e2) {
                e2.printStackTrace();
            }
        }

        return def;
    }


    /**
     * @param graphFile
     * @return
     */
    @Override
    public SameDiff importGraph(String graphFile) {
        return importGraph(new File(graphFile));
    }

    /**
     * @param graphFile
     * @return
     */
    @Override
    public SameDiff importGraph(File graphFile) {
        GRAPH_TYPE def = null;
        try (FileInputStream fis = new FileInputStream(graphFile)) {
            return importGraph(fis);
        } catch (Exception e) {
            e.printStackTrace();

        }

        if (def == null)
            throw new ND4JIllegalStateException("Unknown format: " + graphFile.getAbsolutePath());


        return importGraph(def);
    }

    @Override
    public Map<String, NODE_TYPE> nameIndexForGraph(GRAPH_TYPE graph) {
        List<NODE_TYPE> nodes = getNodeList(graph);
        Map<String, NODE_TYPE> ret = new HashMap<>();
        for (NODE_TYPE node : nodes) {
            ret.put(getName(node), node);
        }
        return ret;
    }

    @Override
    public Map<String, NODE_TYPE> nodesByName(GRAPH_TYPE graph) {
        val nodeTypes = getNodeList(graph);
        Map<String, NODE_TYPE> ret = new LinkedHashMap<>();
        for (int i = 0; i < nodeTypes.size(); i++) {
            ret.put(getName(nodeTypes.get(i)), nodeTypes.get(i));
        }
        return ret;
    }

    /**
     * This method converts given TF
     *
     * @param tfGraph
     * @return
     */
    @Override
    public SameDiff importGraph(GRAPH_TYPE tfGraph) {
        SameDiff diff = SameDiff.create();
        ImportState<GRAPH_TYPE, TENSOR_TYPE> importState = new ImportState<>();
        importState.setSameDiff(diff);
        importState.setGraph(tfGraph);

        val variablesForGraph = variablesForGraph(tfGraph);
        importState.setVariables(variablesForGraph);


        //map the names of the nodes while accumulating the vertex ids for each variable
        Map<String, Boolean> stringNodes = new HashMap<>();      //Key: name of string variable. Value: if it's a constant
        for (Map.Entry<String, TENSOR_TYPE> entry : variablesForGraph.entrySet()) {
            if (shouldSkip((NODE_TYPE) entry.getValue())) {    //TODO only works for TF
                //Skip some nodes, for example reduction indices (a lot of ND4J/SameDiff ops use int[] etc, not an INDArray/Variable)
                continue;
            }

            DataType dt = dataTypeForTensor(entry.getValue());
            INDArray arr = getNDArrayFromTensor(entry.getKey(), entry.getValue(), tfGraph);
            long[] shape = hasShape((NODE_TYPE) entry.getValue()) ? getShape((NODE_TYPE) entry.getValue()) : null;   //TODO only works for TF

            Preconditions.checkNotNull(dt, "Data type is null for variable %s", entry.getKey());
            Preconditions.checkState(dt != DataType.UNKNOWN, "Unknown data type for variable %s", entry.getKey());

            if (isPlaceHolder(entry.getValue())) {
                diff.placeHolder(entry.getKey(), dt, shape);
            } else if (isConstant(entry.getValue())) {
                Preconditions.checkNotNull(arr, "Array is null for placeholder variable %s", entry.getKey());
                diff.constant(entry.getKey(), arr);
            } else {
                //Could be variable, or could be array type (i.e., output of op/"activations")
                //TODO work out which!

                SDVariable v;
                if(shape == null){
                    //No shape -> probably not a variable...
                    v = diff.var(entry.getKey(), VariableType.ARRAY, null, dt, (long[])null);
                } else {
                    v = diff.var(entry.getKey(), dt, shape);
                }
                if (arr != null)
                    diff.associateArrayWithVariable(arr, v);
            }

            NODE_TYPE node = (NODE_TYPE) entry.getValue();      //TODO this only works for TF
            List<String> controlDependencies = getControlDependencies(node);
            if (controlDependencies != null) {
                Variable v = diff.getVariables().get(entry.getKey());
                v.setControlDeps(controlDependencies);
            }
        }

        //handle mapping vertex ids properly
        val tfNodesList = getNodeList(tfGraph);
        for (NODE_TYPE tfNode : tfNodesList) {
            if (!opsToIgnore().contains(getOpType(tfNode)) || isOpIgnoreException(tfNode))
                mapNodeType(tfNode, importState);
        }

        //Handle edge case until multi datatypes is merged: import String constant variables as fixed value 0 variables
        // This is used in assertions and the like - the exact String values aren't important for inference, but we
        // can't perform inference without them
        //Specifically: any string values that aren't the output of an op get a scalar 0 array
        if (!stringNodes.isEmpty()) {
            for (Map.Entry<String, Boolean> e : stringNodes.entrySet()) {
                if (e.getValue()) {
                    //Is a constant String node - can't import, but probably need it for execution...
                    //TODO fix this once dtypes are done
                    diff.getVariable(e.getKey()).setArray(Nd4j.trueScalar(0));
                }
            }
        }

        //Build functionOutputFor - i.e., map from SDVariable -> functions it's an output for (should only ever be 1)
        //Also build outgoingArgsReverse: map from DifferentialFunction name to SDVariable names that are outputs
        Map<String, List<DifferentialFunction>> fnOutputsFor = new LinkedHashMap<>();
        for (SameDiffOp op : diff.getOps().values()) {
            DifferentialFunction df = op.getOp();
            initOutputVariables(diff, df);
        }

        //Validate the graph structure
        validateGraphStructure(diff);

        return diff;
    }

    protected void initOutputVariables(SameDiff sd, DifferentialFunction df) {
        String[] outNames = sd.getOutputsForFunction(df);
        SDVariable[] outVars;
        if (outNames == null) {
            outVars = sd.generateOutputVariableForOp(df, df.getOwnName() != null ? df.getOwnName() : df.opName());
            outNames = new String[outVars.length];
            for (int i = 0; i < outVars.length; i++) {
                outNames[i] = outVars[i].getVarName();
            }
            sd.getOps().get(df.getOwnName()).setOutputsOfOp(Arrays.asList(outNames));
        }

        for (String s : outNames) {
            sd.getVariables().get(s).setOutputOfOp(df.getOwnName());
        }
    }


    @Override
    public boolean validTensorDataType(TENSOR_TYPE tensorType) {
        return dataTypeForTensor(tensorType) != DataType.UNKNOWN;
    }

    public void validateGraphStructure(SameDiff sameDiff) {
        //First: Check placeholders. When SDVariables are added with null shapes, these can be interpreted as a placeholder
        // but null shapes might simply mean shape isn't available during import right when the variable is added
        //Idea here: if a "placeholder" is the output of any function, it's not really a placeholder
        for (SDVariable v : sameDiff.variables()) {
            String name = v.getVarName();
            if (sameDiff.isPlaceHolder(name)) {
                String varOutputOf = sameDiff.getVariables().get(name).getOutputOfOp();
            }
        }

        //Second: check that all op inputs actually exist in the graph
        for (SameDiffOp op : sameDiff.getOps().values()) {
            List<String> inputs = op.getInputsToOp();
            if (inputs == null)
                continue;

            for (String s : inputs) {
                if (sameDiff.getVariable(s) == null) {
                    throw new IllegalStateException("Import validation failed: op \"" + op.getName() + "\" of type " + op.getOp().getClass().getSimpleName()
                            + " has input \"" + s + "\" that does not have a corresponding variable in the graph");
                }
            }
        }
    }

}
