/*
 * Copyright 2017 Google Inc. All Rights Reserved.
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

package com.google.ar.core.examples.java.helloar;

import android.graphics.Color;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.MotionEvent;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Pose;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.helpers.TapHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;

import com.google.ar.sceneform.ux.TransformableNode;
import com.google.ar.sceneform.ux.ArFragment;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This is a simple example that shows how to create an augmented reality (AR) application using the
 * ARCore API. The application will display any detected planes and will allow the user to tap on a
 * plane to place a 3d model of the Android robot.
 */
public class HelloArActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = HelloArActivity.class.getSimpleName();

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private TapHelper tapHelper;
  private TextView textView;

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final ObjectRenderer virtualObjectShadow = new ObjectRenderer();
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];
  private static final float[] DEFAULT_COLOR = new float[] {0f, 0f, 0f, 0f};

  // View Matrix and Projection matrix used in onDrawFrame #function.
  float[] projmtx = new float[16];
  float[] viewmtx = new float[16];

    // Temporary matrices allocated here to reduce number of allocations for each frame.
    private final float[] modelMatrix = new float[16];
    private final float[] modelViewMatrix = new float[16];
    private final float[] modelViewProjectionMatrix = new float[16];


    // Anchors created from taps used for object placing with a given color.
  private static class ColoredAnchor {
    public final Anchor anchor;
    public final float[] color;

    public ColoredAnchor(Anchor a, float[] color4f) {
      this.anchor = a;
      this.color = color4f;
    }
  }

  private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

  private final HashMap<Integer, String> anchorLocationHmap = new HashMap<>();
  private final HashMap<String, Anchor> anchorsInView = new HashMap<>();

  private static int anchorCount = 0;

  DisplayMetrics displayMetrics = new DisplayMetrics();
  public int screenHeight ;//= displayMetrics.heightPixels;
  public int screenWidth ;//= displayMetrics.widthPixels;


  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    textView = findViewById(R.id.textView);
    textView.setBackgroundColor(Color.BLACK);
    displayRotationHelper = new DisplayRotationHelper(/*context=*/ this);

    getWindowManager().getDefaultDisplay().getMetrics(displayMetrics);
    screenHeight = displayMetrics.heightPixels;
    screenWidth = displayMetrics.widthPixels;

    // Set up tap listener.
    tapHelper = new TapHelper(/*context=*/ this);
    surfaceView.setOnTouchListener(tapHelper);

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

    installRequested = false;
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);

      } catch (UnavailableArcoreNotInstalledException
          | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      // In some cases (such as another camera app launching) the camera may be given to
      // a different app instead. Handle this properly by showing a message and recreate the
      // session at the next iteration.
      messageSnackbarHelper.showError(this, "Camera not available. Please restart the app.");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();

    messageSnackbarHelper.showMessage(this, "Searching for surfaces...");
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(/*context=*/ this);
      planeRenderer.createOnGlThread(/*context=*/ this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(/*context=*/ this);

      virtualObject.createOnGlThread(/*context=*/ this, "models/andy.obj", "models/andy.png");
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f,   6.0f);

      virtualObjectShadow.createOnGlThread(
          /*context=*/ this, "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);

    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      // Handle one tap per frame.
      handleTap(frame, camera);
        //camera.getProjectionMatrix();
        Collection<Anchor> al = frame.getUpdatedAnchors();
        for (Anchor a : al)
            a.getPose();

      // Draw background.
      backgroundRenderer.draw(frame);

      // If not tracking, don't draw 3d objects.
      if (camera.getTrackingState() == TrackingState.PAUSED) {
        return;
      }

      // Get projection matrix.
      camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

      // Get camera matrix and draw.
      camera.getViewMatrix(viewmtx, 0);


      // Compute lighting from average intensity of the image.
      // The first three components are color scaling factors.
      // The last one is the average pixel intensity in gamma space.
      final float[] colorCorrectionRgba = new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);

      // Visualize tracked points.
      PointCloud pointCloud = frame.acquirePointCloud();
      pointCloudRenderer.update(pointCloud);
      pointCloudRenderer.draw(viewmtx, projmtx);

      // Application is responsible for releasing the point cloud resources after
      // using it.
      pointCloud.release();


      // Check if we detected at least one plane. If so, hide the loading message.
      if (messageSnackbarHelper.isShowing()) {
        for (Plane plane : session.getAllTrackables(Plane.class)) {
          if (plane.getTrackingState() == TrackingState.TRACKING) {
            messageSnackbarHelper.hide(this);
            break;
          }
        }
      }

      // Visualize planes.
      planeRenderer.drawPlanes(
          session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projmtx);

      // Visualize anchors created by touch.
      float scaleFactor = 1.0f;
      //Log.e("vai:","Session.getAllAnchors="+session.getAllAnchors().size());
      //Log.e("vai:","anchors.size="+anchors.size());
        anchorsInView.clear();
        for (ColoredAnchor coloredAnchor : anchors) {
        if (coloredAnchor.anchor.getTrackingState() != TrackingState.TRACKING) {
            //Log.e("apeks:",coloredAnchor.anchor.hashCode()+":"+coloredAnchor.anchor.getTrackingState());
          continue;
        }

        //Log.e("vai:","anchor="+coloredAnchor.toString());

        //coloredAnchor.anchor.
        // Get the current pose of an Anchor in world space. The Anchor pose is updated
        // during calls to session.update() as ARCore refines its estimate of the world.+
        coloredAnchor.anchor.getPose().toMatrix(anchorMatrix, 0);
        // Get the latest Pose of each anchor and store it in anchorMatrix
        // Now pass this anchorMatrix to openGL functions to draw the 3D object.

        // Update and draw the model and its shadow.
        virtualObject.updateModelMatrix(anchorMatrix, scaleFactor);
        virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
/*
          Log.e("TAG:","Projection Mat==");
          for (int i =0;i<16;i++){
              if(i%4 == 0)
                  System.out.println("\n") ;
              System.out.print(projmtx[i]+",");
          }
              //Log.e("TAG:", Arrays.toString(row));
          Matrix.multiplyMM(modelViewMatrix, 0, viewmtx, 0, anchorMatrix, 0);
          Matrix.multiplyMM(modelViewProjectionMatrix, 0, projmtx, 0, modelViewMatrix, 0);

*/

        float[] world2screenMatrix = virtualObject.getMyScreenMatrix(anchorMatrix,viewmtx, projmtx);
        double[] anchor_2d = world2Screen(screenWidth,screenHeight,world2screenMatrix);

          //Log.e("vaibh","ScreenWidth = "+screenWidth+" ScreenHeight = "+screenHeight);
          //Log.e("vaibh","Anchor X = "+anchor_2d[0]+" Y = "+anchor_2d[1]);

          virtualObject.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);
          virtualObjectShadow.draw(viewmtx, projmtx, colorCorrectionRgba, coloredAnchor.color);

          if((anchor_2d[0] > 0 && anchor_2d[0] < screenWidth) && (anchor_2d[1] > 0 && anchor_2d[1] < screenHeight)) {
              Log.e("apeks", "Anchor is visible on the screen::"+coloredAnchor.anchor.hashCode());
              String anchorId = anchorLocationHmap.get(coloredAnchor.anchor.hashCode());
              //Log.e("vaibh", "You're seeing Anchor: " + anchorId);
              anchorsInView.put(anchorId, coloredAnchor.anchor);
              //messageSnackbarHelper.showMessage(this,"You're seeing Anchor: " + anchorLocationHmap.get(coloredAnchor.anchor.hashCode()));
          }
          Log.e("apeks", "Anchor is NOT visible on the screen::"+coloredAnchor.anchor.hashCode());
      }

      /**
        This is just a temporarily logic for demonstration pursposes...
       */
        Log.e("vaibh", "Anchors in View size = "+anchorsInView.size());
        if(anchorsInView.size() > 0) {
            String displayAnchorIds = "";

            for (Map.Entry<String, Anchor> entry : anchorsInView.entrySet())
                displayAnchorIds = displayAnchorIds + entry.getKey() + ", ";

            displayAnchorIds = displayAnchorIds.substring(0,displayAnchorIds.length()-2);

            if(anchorsInView.size() > 0){
                String nearestAnchorId = getClosestAnchor(frame, camera, anchorsInView);
                displayAnchorIds += "\nNearest Anchor: "+nearestAnchorId.split(":")[0
                        ] +String.format("\nAt %.3f",Float.valueOf(nearestAnchorId.split(":")[1]))+" meters away.";
            }
            final String anchorsDisplayed = displayAnchorIds;
            runOnUiThread(() -> textView.setText("I'm seeing Anchor: " + anchorsDisplayed));
        }
        else
            runOnUiThread(() -> textView.setText("I don't see any markers on the screen :("));

      Log.e("vaibh","anchorsInView size = "+anchorsInView.size());
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  double[] world2Screen(int screenWidth, int screenHeight, float[] world2cameraMatrix)
  {
    float[] origin = {0f, 0f, 0f, 1f};
    float[] deviceScreenMatrix = new float[4];
    Matrix.multiplyMV(deviceScreenMatrix, 0,  world2cameraMatrix, 0,  origin, 0);

    deviceScreenMatrix[0] = deviceScreenMatrix[0]/deviceScreenMatrix[3];
    deviceScreenMatrix[1] = deviceScreenMatrix[1]/deviceScreenMatrix[3];

    double[] pos_2d = new double[]{0,0};
    pos_2d[0] = screenWidth  * ((deviceScreenMatrix[0] + 1.0)/2.0);
    pos_2d[1] = screenHeight * (( 1.0 - deviceScreenMatrix[1])/2.0);

    return pos_2d;
  }

  // Handle only one tap per frame, as taps are usually low frequency compared to frame rate.
  private void handleTap(Frame frame, Camera camera) {
    MotionEvent tap = tapHelper.poll();
    if (tap == null) Log.e("kala:","Null taps");
    else Log.e("kala:","Good taps");
    if (tap != null && camera.getTrackingState() == TrackingState.TRACKING) {
      for (HitResult hit : frame.hitTest(tap)) {
        // Check if any plane was hit, and if it was hit inside the plane polygon
        Trackable trackable = hit.getTrackable();

          /**
           *
           *  trackable.getAnchors() returns anchors attached to it.
           *  So, check if in the current frame any anchors are there.
           *  God hope this works, I need this very to work badly......
           */
        // Creates an anchor if a plane or an oriented point was hit.
        if ((trackable instanceof Plane
                && ((Plane) trackable).isPoseInPolygon(hit.getHitPose())
                && (PlaneRenderer.calculateDistanceToPlane(hit.getHitPose(), camera.getPose()) > 0))
            || (trackable instanceof Point
                && ((Point) trackable).getOrientationMode()
                    == OrientationMode.ESTIMATED_SURFACE_NORMAL)) {
          // Hits are sorted by depth. Consider only closest hit on a plane or oriented point.
          // Cap the number of objects created. This avoids overloading both the
          // rendering system and ARCore.
          if (anchors.size() >= 25) {
            anchors.get(0).anchor.detach();
            anchorLocationHmap.remove(anchors.remove(0).anchor.hashCode()); // Remove corresponding entry here.
          }

          // Assign a color to the object for rendering based on the trackable type
          // this anchor attached to. For AR_TRACKABLE_POINT, it's blue color, and
          // for AR_TRACKABLE_PLANE, it's green color.
          float[] objColor;
          if (trackable instanceof Point) {
            objColor = new float[] {66.0f, 133.0f, 244.0f, 255.0f};
          } else if (trackable instanceof Plane) {
            objColor = new float[] {139.0f, 195.0f, 74.0f, 255.0f};
          } else {
            objColor = DEFAULT_COLOR;
          }

          Anchor anc = hit.createAnchor();

           // TransformableNode andy = new TransformableNode();
          //Log.e("kap:","Anchor hashcode:Distance = "+anc.hashCode()+"::"+hit.getDistance());
          //Toast.makeText(getApplicationContext(),"\"Anchor hashcode = \"+anc.hashCode()",Toast.LENGTH_LONG).show();
          // Adding an Anchor tells ARCore that it should track this position in
          // space. This anchor is created on the Plane to place the 3D model
          // in the correct position relative both to the world and to the plane.
          anchors.add(new ColoredAnchor(anc, objColor));
          Log.e("TAG","Putting "+anc.hashCode() +":::"+ Integer.toString(anchorLocationHmap.size()));
          anchorLocationHmap.put(anc.hashCode(), Integer.toString(++anchorCount)); // Store anchor hash and its distance from camera
            Log.e("TAG","New size anchorLocHM= "+anchorLocationHmap.size()+":::anchors list="+anchors.size());
            for (Map.Entry<Integer, String> e : anchorLocationHmap.entrySet())
                Log.e("TAG","Key: "+e.getKey()+" Val: "+e.getValue());
          break;

          // Fuck this'nt not working........
        }
      }
    }
  }

    /**
     *  Function to find the anchor closest to the device based on the set of visible anchors.
     */
    private String getClosestAnchor(Frame frame, Camera camera, HashMap<String, Anchor> anchorsIdsInView) {

        float nearestAnchorDistance = Float.MAX_VALUE;
        String nearestAnchorId = "Unknown";
        Pose devicePose = camera.getPose();
        Pose markerPose;

        /*for (String s : anchorsIdsInView)
            System.out.println("APEKS ## "+s);
        */


        for (Map.Entry<String, Anchor> entry : anchorsIdsInView.entrySet()) {
            markerPose = entry.getValue().getPose();
            float dx = markerPose.tx() - devicePose.tx();
            float dy = markerPose.ty() - devicePose.ty();
            float dz = markerPose.tz() - devicePose.tz();

            float distanceMeters = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

            if (distanceMeters < nearestAnchorDistance) {
                nearestAnchorDistance = distanceMeters;
                nearestAnchorId = entry.getKey();
                //Log.e("apeks:","Near ID: "+nearestAnchorId + " Distance= "+nearestAnchorDistance);
            }

        }

        /*for (ColoredAnchor a: ca) {
            Log.e("apeks:"," a = "+a.anchor.hashCode());
            if (anchorsIdsInView.contains(Integer.toString(a.anchor.hashCode()))) {
                markerPose = a.anchor.getPose();
                float dx = markerPose.tx() - devicePose.tx();
                float dy = markerPose.ty() - devicePose.ty();
                float dz = markerPose.tz() - devicePose.tz();

                float distanceMeters = (float) Math.sqrt(dx*dx + dy*dy + dz*dz);

                if (distanceMeters < nearestAnchorDistance) {
                    nearestAnchorDistance = distanceMeters;
                    nearestAnchorId = a.anchor.hashCode();
                    Log.e("apeks:","Near ID: "+nearestAnchorId);
                }
            }
        }*/
        return nearestAnchorId+":"+nearestAnchorDistance;
    }

    /**
     * Callback function that is invoked when the OK button in the resolve dialog is pressed.
     *
     * @param dialogValue The value entered in the resolve dialog.
     */
    private void onResolveOkPressed(String dialogValue) {

    }

}
