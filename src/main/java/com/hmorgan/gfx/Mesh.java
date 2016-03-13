package com.hmorgan.gfx;

import com.hackoeur.jglm.Vec3;
import com.jogamp.common.nio.Buffers;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.util.WWBufferUtil;
import gov.nasa.worldwind.util.WWUtil;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Represents a generic 3D mesh. A mesh can be a polygonal, polyline,
 * or a point-based mesh.
 * <p>
 * This class is <i>immutable</i> and uses a builder class. This makes this class
 * inheritly thread-safe.
 *
 * @author Hunter N. Morgan
 */
public class Mesh {

    private String name;
    protected  List<Vertex> vertices;
//    protected FloatBuffer vertices;     // v0x/v0y/v0z/v1x/v1y/v1z...
//    protected FloatBuffer normals;      // n0x/n0y/n0z/n1x/n1y/n1z...
//    protected FloatBuffer textureCoords;// t0x/v0y/t1x/t1y...
    protected IntBuffer indices;        // v1/v2/v3 or v1/n1/v2/n2 or /v1/t1/n1/v2/t2/n2
    protected FloatBuffer vboBuf;       // vvvvnnnn (if no normals, then just vvvv)

    private int[] vboIds;               // vertex buffer object ids
    private int[] eboIds;               // element buffer object ids
    private boolean generatedGlBuffers;

    public enum MeshType {
        POINTS_MESH,            // mesh contains just points
        POLYLINE_MESH,          // mesh is a polygon (triangle mesh)
        POLYGON_MESH            // mesh is a polyline (line segments)
    }

    protected MeshType meshType;


    public static final class Builder {
        private String name;
        private List<Vertex> vertices;
        private IntBuffer indices;
        private MeshType meshType;

        public Builder() {
            vertices = new ArrayList<>();
            meshType = MeshType.POLYGON_MESH;   // most common
        }

        public Builder setName(String val) {
            name = val;
            return this;
        }

        public Builder setVertices(List<Vertex> val) {
            vertices = val;
            return this;
        }

        public Builder setIndices(IntBuffer val) {
            indices = val;
            return this;
        }

        public Builder setMeshType(MeshType val) {
            meshType = val;
            return this;
        }

        public Mesh build() {
            return new Mesh(this);
        }
    }

    private Mesh(Builder builder) {
        name = builder.name;
        vertices = builder.vertices;
//        normals = builder.normals;
//        textureCoords = builder.textureCoords;
        indices = builder.indices;
        meshType = builder.meshType;
        vboIds = new int[1];
        eboIds = new int[1];
        generatedGlBuffers = false;

        // create VBO buffer, if normals exist then layout is vvvvnnnn, else its just vvvv

        // create VBO, layout is vvvnnntttvvvnnnttt...
        int vboBufSize = vertices.size()*8;
//        if(getNormals().isPresent())
//            vboBufSize += getNormals().get().limit();
        vboBuf = FloatBuffer.allocate(vboBufSize);
        for(Vertex v : vertices) {
            vboBuf.put(v.getPosition().getX());
            vboBuf.put(v.getPosition().getY());
            vboBuf.put(v.getPosition().getZ());

            v.getNormal().ifPresent(n -> {
                vboBuf.put(n.getX());
                vboBuf.put(n.getY());
                vboBuf.put(n.getZ());
            });

            v.getTexCoord().ifPresent(t -> {
                vboBuf.put(t.getX());
                vboBuf.put(t.getY());
            });
        }

//        vboBuf.put(vertices);
//        if(getNormals().isPresent())
//            vboBuf.put(normals);
        vboBuf.flip();
    }

    public void genGlBuffers(DrawContext dc) {
        final GL2 gl = dc.getGL().getGL2();
        try {
            gl.glGenBuffers(1, vboIds, 0);                      // gen 1 buffer for VBO
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, vboIds[0]);     // bind buffer ID as VBO
            gl.glBufferData(GL.GL_ARRAY_BUFFER, vboBuf.limit() * Buffers.SIZEOF_FLOAT, vboBuf.rewind(), GL.GL_STATIC_DRAW);   // copy data to buffer
            generatedGlBuffers = true;
        } finally {
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);             // unbind buffer
        }

        getIndices().ifPresent(indices -> {
            try {
                gl.glGenBuffers(1, eboIds, 0);                              // gen 1 buffer for EBO
                gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, eboIds[0]);     // bind buffer ID as EBO
                gl.glBufferData(GL.GL_ELEMENT_ARRAY_BUFFER, indices.limit() * Buffers.SIZEOF_INT, indices.rewind(), GL.GL_STATIC_DRAW);   // copy data to buffer
            } finally {
                gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0);             // unbind buffer
            }
        });
    }

    public String getName() {
        return name;
    }

    public List<Vertex> getVertices() {
        return vertices;
    }

    public Optional<IntBuffer> getIndices() {
        return Optional.ofNullable(indices);
    }

    public FloatBuffer getVboBuf() {
        return vboBuf;
    }

    public int[] getVboIds() {
        return vboIds;
    }

    public int[] getEboIds() {
        return eboIds;
    }

    public boolean isGeneratedGlBuffers() {
        return generatedGlBuffers;
    }

    public MeshType getMeshType() {
        return meshType;
    }

    @Override
    public boolean equals(Object o) {
        if(this == o) return true;
        if(o == null || getClass() != o.getClass()) return false;

        Mesh mesh = (Mesh) o;

        if(generatedGlBuffers != mesh.generatedGlBuffers) return false;
        if(!name.equals(mesh.name)) return false;
        if(!vertices.equals(mesh.vertices)) return false;
        if(indices != null ? !indices.equals(mesh.indices) : mesh.indices != null) return false;
        if(!vboBuf.equals(mesh.vboBuf)) return false;
        if(!Arrays.equals(vboIds, mesh.vboIds)) return false;
        if(!Arrays.equals(eboIds, mesh.eboIds)) return false;
        return meshType == mesh.meshType;

    }

    @Override
    public int hashCode() {
        int result = name.hashCode();
        result = 31 * result + vertices.hashCode();
        result = 31 * result + (indices != null ? indices.hashCode() : 0);
        result = 31 * result + vboBuf.hashCode();
        result = 31 * result + Arrays.hashCode(vboIds);
        result = 31 * result + Arrays.hashCode(eboIds);
        result = 31 * result + (generatedGlBuffers ? 1 : 0);
        result = 31 * result + meshType.hashCode();
        return result;
    }
}
