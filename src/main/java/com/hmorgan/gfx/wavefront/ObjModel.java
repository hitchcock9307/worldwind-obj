package com.hmorgan.gfx.wavefront;

import com.hackoeur.jglm.Vec3;
import com.hmorgan.gfx.Mesh;
import com.jogamp.common.nio.Buffers;
import gov.nasa.worldwind.geom.*;
import gov.nasa.worldwind.pick.PickSupport;
import gov.nasa.worldwind.render.DrawContext;
import gov.nasa.worldwind.render.OrderedRenderable;
import gov.nasa.worldwind.render.Renderable;
import gov.nasa.worldwind.util.OGLStackHandler;
import gov.nasa.worldwind.util.OGLUtil;

import javax.media.opengl.GL;
import javax.media.opengl.GL2;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

/**
 * Represents a Wavefront .OBJ 3d model. This class directly references a .OBJ
 * file. Upon construction, the .OBJ file is parsed and the meshes/objects are
 * extracted.
 * <p>
 * This parsing only supports some features of the Wavefront .OBJ specification.
 * Those features are:
 * <ul>
 *     <li>'o' - objects</li>
 *     <li>'v' - vertices</li>
 *     <li>'vn' - vertex normals</li>
 *     <li>'vt' - texture coordinates</li>
 *     <li>'f' - faces</li>
 *     <li>'l' - lines</li>
 * </ul>
 * Each object in the .OBJ file are represented in this class as a {@link Mesh} object,
 * and are stored in a map keyed by their name.
 *
 * @author Hunter N. Morgan
 */
public class ObjModel implements OrderedRenderable {

    private Map<String, Mesh> meshes;       // collection of Meshes

    private Position position;              // geographic position of the cube

    // Determined each frame
    protected long frameTimestamp = -1L;    // frame timestamp, increments during each render cycle
    protected Vec4 placePoint;              // cartesian position of the cube, computed from #position
    protected double eyeDistance;           // distance from the eye point to the cube
    protected Extent extent;                //

    private static final OGLStackHandler oglStackHandler = new OGLStackHandler(); // used in beginDrawing/endDrawing
    protected PickSupport pickSupport = new PickSupport();

    private double size;


    private ObjModel() {
        position = Position.ZERO;
    }

    /**
     * Constructs a new ObjModel.
     *
     * @param fileName String filename of .OBJ file
     * @throws IOException
     */
    public ObjModel(String fileName) throws IOException {
        this();
        this.meshes = ObjLoader.loadObjMeshes(fileName);
    }

    /**
     * Constructs a new ObjModel.
     *
     * @param filePath Path to .OBJ file
     * @throws IOException
     */
    public ObjModel(Path filePath) throws IOException {
        this();
        this.meshes = ObjLoader.loadObjMeshes(filePath);
    }

    /**
     * Constructs a new ObjModel.
     *
     * @param meshMap Map of all meshes for this ObjModel
     */
    public ObjModel(Map<String, Mesh> meshMap) {
        this.meshes = meshMap;
    }

    @Override
    public void render(DrawContext dc) {
        // 1) Set up drawing state
        // 2) Apply transform to position cube
        // 3) Draw the cube
        // 4) Restore drawing state to default

        // Rendering is controlled by NASA WorldWind's SceneController
        // The render cycle looks like this:
        // Render is called three times:
        // 1) During picking. The cube is drawn in a single color.
        // 2) As a normal renderable. The cube is added to the ordered renderable queue.
        // 3) As an OrderedRenderable. The cube is drawn.

        if (this.extent != null)
        {
            if (!this.intersectsFrustum(dc))
                return;

            // If the shape is less that a pixel in size, don't render it.
            if (dc.isSmall(this.extent, 1))
                return;
        }

        if(dc.isOrderedRenderingMode()) {
            drawObjModel(dc);
//            drawOrderedRenderableCube(dc, this.pickSupport);
        } else {
            makeOrderedRenderable(dc);
        }
        int i = 0;

//        if(dc.isOrderedRenderingMode()) {
//            drawOrderedRenderableCube(dc, this.pickSupport);
//        } else {
//            makeOrderedRenderable(dc);
//        }
    }

    @Override
    public void pick(DrawContext dc, Point point) {
        render(dc);
    }

    @Override
    public double getDistanceFromEye() {
        return this.eyeDistance;
    }

    /**
     * Setup drawing state in preparation for drawing. State changed by this method must be
     * restored in endDrawing.
     *
     * @param dc Active draw context.
     */
    public void beginDrawing(DrawContext dc) {
        final GL2 gl = dc.getGL().getGL2();
//        final int attrMask = GL2.GL_CURRENT_BIT | GL2.GL_COLOR_BUFFER_BIT;
        final int attrMask = GL2.GL_CURRENT_BIT
                | GL2.GL_DEPTH_BUFFER_BIT
                | GL2.GL_LINE_BIT | GL2.GL_HINT_BIT // for outlines
                | GL2.GL_COLOR_BUFFER_BIT // for blending
                | GL2.GL_TRANSFORM_BIT // for texture
                | GL2.GL_POLYGON_BIT; // for culling


        // use stack handler to allow us to push attribs
        oglStackHandler.clear();
        oglStackHandler.pushAttrib(gl, attrMask);
        oglStackHandler.pushModelview(gl);
        oglStackHandler.pushClientAttrib(gl, GL2.GL_CLIENT_VERTEX_ARRAY_BIT);
        gl.glEnableClientState(GL2.GL_VERTEX_ARRAY); // all drawing uses vertex arrays

        // enable lighting if not in picking mode
        if(!dc.isPickingMode()) {
            dc.beginStandardLighting();
            gl.glEnable(GL.GL_LINE_SMOOTH);
            gl.glEnable(GL.GL_BLEND);
            OGLUtil.applyBlending(gl, false);

            gl.glEnable(GL2.GL_LIGHTING);
            gl.glEnableClientState(GL2.GL_NORMAL_ARRAY);

            // Were applying a scale transform on the modelview matrix, so the normal vectors must be re-normalized
            // before lighting is computed.
            gl.glEnable(GL2.GL_NORMALIZE);

            gl.glHint(GL.GL_LINE_SMOOTH_HINT, GL.GL_NICEST);
        }

        // Multiply the modelview matrix by a surface orientation matrix to set up a local coordinate system with the
        // origin at the cube's center position, the Y axis pointing North, the X axis pointing East, and the Z axis
        // normal to the globe.
        gl.glMatrixMode(GL2.GL_MODELVIEW);

        Matrix matrix = dc.getGlobe().computeSurfaceOrientationAtPosition(this.position);
        matrix = dc.getView().getModelviewMatrix().multiply(matrix);

        double[] matrixArray = new double[16];
        matrix.toArray(matrixArray, 0, false);
        gl.glLoadMatrixd(matrixArray, 0);
    }

    /**
     * Restore drawing state changed in beginDrawing to the default.
     *
     * @param dc Active draw context.
     */
    public void endDrawing(DrawContext dc) {
        final GL2 gl = dc.getGL().getGL2();

        if(!dc.isPickingMode()) {
            dc.endStandardLighting();
            gl.glDisable(GL.GL_LINE_SMOOTH);
            gl.glDisable(GL.GL_BLEND);

            gl.glDisable(GL2.GL_LIGHTING);
            gl.glDisableClientState(GL2.GL_NORMAL_ARRAY);

            // Were applying a scale transform on the modelview matrix, so the normal vectors must be re-normalized
            // before lighting is computed.
            gl.glDisable(GL2.GL_NORMALIZE);
        }

        gl.glDisableClientState(GL2.GL_VERTEX_ARRAY);
        oglStackHandler.pop(gl);
    }

    /**
     * Compute per-frame attributes, and add the ordered renderable to the ordered renderable list.
     *
     * @param dc Current draw context.
     */
    protected void makeOrderedRenderable(DrawContext dc)
    {
        // TODO: VBOs should be created here
        meshes.values()
                .stream()
                .filter(mesh -> !mesh.isGeneratedGlBuffers())
                .forEach(mesh -> mesh.genGlBuffers(dc));

        // This method is called twice each frame: once during picking and once during rendering. We only need to
        // compute the placePoint and eye distance once per frame, so check the frame timestamp to see if this is a
        // new frame.
        if (dc.getFrameTimeStamp() != this.frameTimestamp)
        {
            // Convert the cube's geographic position to a position in Cartesian coordinates.
            this.placePoint = dc.getGlobe().computePointFromPosition(this.position);

            // Compute the distance from the eye to the cube's position.
            this.eyeDistance = dc.getView().getEyePoint().distanceTo3(this.placePoint);

            // Compute a sphere that encloses the cube. We'll use this sphere for intersection calculations to determine
            // if the cube is actually visible.
            this.extent = new Sphere(this.placePoint, Math.sqrt(3.0) * size / 2.0);

            this.frameTimestamp = dc.getFrameTimeStamp();
        }

        // Add the cube to the ordered renderable list. The SceneController sorts the ordered renderables by eye
        // distance, and then renders them back to front. render will be called again in ordered rendering mode, and at
        // that point we will actually draw the cube.
        dc.addOrderedRenderable(this);
    }

    /**
     * Draws this Obj model.
     *
     * @param dc Current draw context.
     */
    private void drawObjModel(DrawContext dc) {
        final GL2 gl = dc.getGL().getGL2();
        beginDrawing(dc);
        try {

            if (dc.isPickingMode())
            {
                Color pickColor = dc.getUniquePickColor();
                pickSupport.addPickableObject(pickColor.getRGB(), this, this.position);
                gl.glColor3ub((byte) pickColor.getRed(), (byte) pickColor.getGreen(), (byte) pickColor.getBlue());
            }

            gl.glScaled(size, size, size);
            // for each mesh, draw it
            meshes.values().forEach(mesh -> {
                gl.glBindBuffer(GL.GL_ARRAY_BUFFER, mesh.getVboIds()[0]);
                gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, mesh.getEboIds()[0]);

                // VBO layout: vvvnnntttvvvnnnttt or just vvvnnnvvvnnn (interleaved)
//                gl.glVertexPointer(3, GL.GL_FLOAT, 0, 0);
                gl.glVertexPointer(3, GL.GL_FLOAT, Buffers.SIZEOF_FLOAT*6, 0);

                if(!dc.isPickingMode())
                    gl.glNormalPointer(GL.GL_FLOAT, Buffers.SIZEOF_FLOAT*6, Buffers.SIZEOF_FLOAT*3);

//                gl.glDrawElements(GL.GL_TRIANGLES, mesh.getIndices().get().limit(), GL.GL_UNSIGNED_INT, 0);
                gl.glDrawArrays(GL.GL_TRIANGLES, 0, (mesh.getVboBuf().limit() / 2)/3 );
//                gl.glDrawElements(GL.GL_TRIANGLES, mesh.getIndices().get().limit(), GL.GL_UNSIGNED_INT, 0);
            });
        } finally {
            gl.glBindBuffer(GL.GL_ARRAY_BUFFER, 0);
            gl.glBindBuffer(GL.GL_ELEMENT_ARRAY_BUFFER, 0);
            endDrawing(dc);
        }
    }




    public Position getPosition() {
        return position;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public Map<String, Mesh> getMeshes() {
        return meshes;
    }

    public double getSize() {
        return size;
    }

    public void setSize(double size) {
        this.size = size;
    }


// CUBE STUFF

    /**
     * Determines whether the cube intersects the view frustum.
     *
     * @param dc the current draw context.
     *
     * @return true if this cube intersects the frustum, otherwise false.
     */
    protected boolean intersectsFrustum(DrawContext dc)
    {
        if (this.extent == null)
            return true; // don't know the visibility, shape hasn't been computed yet

        if (dc.isPickingMode())
            return dc.getPickFrustums().intersectsAny(this.extent);

        return dc.getView().getFrustumInModelCoordinates().intersects(this.extent);
    }

    /**
     * Set up drawing state, and draw the cube. This method is called when the cube is rendered in ordered rendering
     * mode.
     *
     * @param dc Current draw context.
     */
    protected void drawOrderedRenderableCube(DrawContext dc, PickSupport pickCandidates)
    {
        this.beginDrawingCube(dc);
        try
        {
            GL2 gl = dc.getGL().getGL2(); // GL initialization checks for GL2 compatibility.
            if (dc.isPickingMode())
            {
                Color pickColor = dc.getUniquePickColor();
                pickCandidates.addPickableObject(pickColor.getRGB(), this, this.position);
                gl.glColor3ub((byte) pickColor.getRed(), (byte) pickColor.getGreen(), (byte) pickColor.getBlue());
            }

            // Render a unit cube and apply a scaling factor to scale the cube to the appropriate size.
            gl.glScaled(size,size,size);
            this.drawUnitCube(dc);
        }
        finally
        {
            this.endDrawingCube(dc);
        }
    }

    /**
     * Draw a unit cube, using the active modelview matrix to orient the shape.
     *
     * @param dc Current draw context.
     */
    protected void drawUnitCube(DrawContext dc)
    {
        // Vertices of a unit cube, centered on the origin.
        float[][] v = {{-0.5f, 0.5f, -0.5f}, {-0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, 0.5f}, {0.5f, 0.5f, -0.5f},
                {-0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, 0.5f}, {0.5f, -0.5f, -0.5f}, {-0.5f, -0.5f, -0.5f}};

        // Array to group vertices into faces
        int[][] faces = {{0, 1, 2, 3}, {2, 5, 6, 3}, {1, 4, 5, 2}, {0, 7, 4, 1}, {0, 7, 6, 3}, {4, 7, 6, 5}};

        // Normal vectors for each face
        float[][] n = {{0, 1, 0}, {1, 0, 0}, {0, 0, 1}, {-1, 0, 0}, {0, 0, -1}, {0, -1, 0}};

        // Note: draw the cube in OpenGL immediate mode for simplicity. Real applications should use vertex arrays
        // or vertex buffer objects to achieve better performance.
        GL2 gl = dc.getGL().getGL2(); // GL initialization checks for GL2 compatibility.
        gl.glBegin(GL2.GL_QUADS);
        try
        {
            for (int i = 0; i < faces.length; i++)
            {
                gl.glNormal3f(n[i][0], n[i][1], n[i][2]);

                for (int j = 0; j < faces[0].length; j++)
                {
                    gl.glVertex3f(v[faces[i][j]][0], v[faces[i][j]][1], v[faces[i][j]][2]);
                }
            }
        }
        finally
        {
            gl.glEnd();
        }
    }

    /**
     * Setup drawing state in preparation for drawing the cube. State changed by this method must be restored in
     * endDrawing.
     *
     * @param dc Active draw context.
     */
    protected void beginDrawingCube(DrawContext dc)
    {
        GL2 gl = dc.getGL().getGL2(); // GL initialization checks for GL2 compatibility.

        int attrMask = GL2.GL_CURRENT_BIT | GL2.GL_COLOR_BUFFER_BIT;

        gl.glPushAttrib(attrMask);

        if (!dc.isPickingMode())
        {
            dc.beginStandardLighting();
            gl.glEnable(GL.GL_BLEND);
            OGLUtil.applyBlending(gl, false);

            // Were applying a scale transform on the modelview matrix, so the normal vectors must be re-normalized
            // before lighting is computed.
            gl.glEnable(GL2.GL_NORMALIZE);
        }

        // Multiply the modelview matrix by a surface orientation matrix to set up a local coordinate system with the
        // origin at the cube's center position, the Y axis pointing North, the X axis pointing East, and the Z axis
        // normal to the globe.
        gl.glMatrixMode(GL2.GL_MODELVIEW);

        Matrix matrix = dc.getGlobe().computeSurfaceOrientationAtPosition(this.position);
        matrix = dc.getView().getModelviewMatrix().multiply(matrix);

        double[] matrixArray = new double[16];
        matrix.toArray(matrixArray, 0, false);
        gl.glLoadMatrixd(matrixArray, 0);
    }

    /**
     * Restore drawing state changed in beginDrawing to the default.
     *
     * @param dc Active draw context.
     */
    protected void endDrawingCube(DrawContext dc)
    {
        GL2 gl = dc.getGL().getGL2(); // GL initialization checks for GL2 compatibility.

        if (!dc.isPickingMode())
            dc.endStandardLighting();

        gl.glPopAttrib();
    }




}
