package com.handapp.mediapipebluetooth;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.graphics.SurfaceTexture;
import android.os.Bundle;
import android.util.Log;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.google.mediapipe.components.CameraHelper;
import com.google.mediapipe.components.CameraXPreviewHelper;
import com.google.mediapipe.components.ExternalTextureConverter;
import com.google.mediapipe.components.FrameProcessor;
import com.google.mediapipe.components.PermissionHelper;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmark;
import com.google.mediapipe.formats.proto.LandmarkProto.NormalizedLandmarkList;
import com.google.mediapipe.framework.AndroidAssetUtil;
import com.google.mediapipe.framework.AndroidPacketCreator;
import com.google.mediapipe.framework.Packet;
import com.google.mediapipe.framework.PacketGetter;
import com.google.mediapipe.glutil.EglManager;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import mikera.arrayz.Array;
import mikera.arrayz.Arrayz;
import mikera.arrayz.INDArray;
import mikera.arrayz.NDArray;
import mikera.matrixx.Matrix;
import mikera.matrixx.algo.Determinant;
import mikera.matrixx.impl.IdentityMatrix;
import mikera.matrixx.impl.TransposedMatrix;
import mikera.matrixx.impl.VectorMatrixM3;
import mikera.vectorz.Vector;
import mikera.vectorz.Vector3;
/**
 * Main activity of MediaPipe example apps.
 */
public class MediapipeFragment extends Fragment {
    private static final String TAG = "MainActivity";
    private static final String BINARY_GRAPH_NAME = "hand_tracking_mobile_gpu.binarypb";
    private static final String INPUT_VIDEO_STREAM_NAME = "input_video";
    private static final String OUTPUT_VIDEO_STREAM_NAME = "output_video";
    private static final String OUTPUT_LANDMARKS_STREAM_NAME = "hand_landmarks";
    private static final String INPUT_NUM_HANDS_SIDE_PACKET_NAME = "num_hands";
    private static final int NUM_HANDS = 1;
    private static final CameraHelper.CameraFacing CAMERA_FACING = CameraHelper.CameraFacing.FRONT;
    // Flips the camera-preview frames vertically before sending them into FrameProcessor to be
    // processed in a MediaPipe graph, and flips the processed frames back when they are displayed.
    // This is needed because OpenGL represents images assuming the image origin is at the bottom-left
    // corner, whereas MediaPipe in general assumes the image origin is at top-left.
    private static final boolean FLIP_FRAMES_VERTICALLY = true;

    static {
        // Load all native libraries needed by the app.
        System.loadLibrary("mediapipe_jni");
        System.loadLibrary("opencv_java3");
    }

    // {@link SurfaceTexture} where the camera-preview frames can be accessed.
    private SurfaceTexture previewFrameTexture;
    // {@link SurfaceView} that displays the camera-preview frames processed by a MediaPipe graph.
    private SurfaceView previewDisplayView;
    // Creates and manages an {@link EGLContext}.
    private EglManager eglManager;
    // Sends camera-preview frames into a MediaPipe graph for processing, and displays the processed
    // frames onto a {@link Surface}.
    private FrameProcessor processor;
    // Converts the GL_TEXTURE_EXTERNAL_OES texture from Android camera into a regular texture to be
    // consumed by {@link FrameProcessor} and the underlying MediaPipe graph.
    private ExternalTextureConverter converter;
    // ApplicationInfo for retrieving metadata defined in the manifest.
    private ApplicationInfo applicationInfo;
    // Handles camera access via the {@link CameraX} Jetpack support library.
    private CameraXPreviewHelper cameraHelper;
    //The stop and record toggle button for sending hand values over bluetooth.
    private Button btnSend;
    //The context from the inflater
    private Context context;
    private boolean timerRunning;

    public static MediapipeFragment newInstance() {
        return new MediapipeFragment();
    }

    public interface MediapipeInterface {
        void sendDataFromMedipipe(String data);
    }

    MediapipeInterface mediapipeInterface;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        context = inflater.getContext();
        View view = inflater.inflate(R.layout.mediapipe_fragment, container, false);
        previewDisplayView = new SurfaceView(context);

        try {
            applicationInfo =
                    getActivity().getPackageManager().getApplicationInfo(getActivity().getPackageName(), PackageManager.GET_META_DATA);
            initMediapipe();
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Cannot find application info: " + e);
        }

        setupPreviewDisplayView(view);

        return view;
    }

    @Override
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        Activity activity = (Activity) context;

        try {
            mediapipeInterface = (MediapipeInterface) activity;
        } catch(RuntimeException a) {
            throw new RuntimeException((activity.toString() + "Must implement Method"));
        }
    }

    private void initMediapipe() {
        // Initialize asset manager so that MediaPipe native libraries can access the app assets, e.g.,
        // binary graphs.
        AndroidAssetUtil.initializeNativeAssetManager(context);
        eglManager = new EglManager(null);
        processor =
                new FrameProcessor(
                        getContext(),
                        eglManager.getNativeContext(),
                        BINARY_GRAPH_NAME,
                        INPUT_VIDEO_STREAM_NAME,
                        OUTPUT_VIDEO_STREAM_NAME);
        processor
                .getVideoSurfaceOutput()
                .setFlipY(FLIP_FRAMES_VERTICALLY);

        PermissionHelper.checkAndRequestCameraPermissions(getActivity());
        AndroidPacketCreator packetCreator = processor.getPacketCreator();
        Map<String, Packet> inputSidePackets = new HashMap<>();
        inputSidePackets.put(INPUT_NUM_HANDS_SIDE_PACKET_NAME, packetCreator.createInt32(NUM_HANDS));
        processor.setInputSidePackets(inputSidePackets);

        processor.addPacketCallback(
                OUTPUT_LANDMARKS_STREAM_NAME,
                (packet) -> {
                    List<NormalizedLandmarkList> multiHandLandmarks =
                            PacketGetter.getProtoVector(packet, NormalizedLandmarkList.parser());
                    if (timerRunning) {
                        String data = getAnglesOfFingersString(multiHandLandmarks);
                        mediapipeInterface.sendDataFromMedipipe(data);
                        Log.i(TAG, "" + getAnglesOfFingersString(multiHandLandmarks));
                    } else {
                        return;
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        converter =
                new ExternalTextureConverter(
                        eglManager.getContext(), 2);
        converter.setFlipY(FLIP_FRAMES_VERTICALLY);
        converter.setConsumer(processor);
        if (PermissionHelper.cameraPermissionsGranted((Activity) context)) {
            startCamera();
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        converter.close();

        // Hide preview display until we re-open the camera again.
        previewDisplayView.setVisibility(View.GONE);
    }

    @Override
    public void onRequestPermissionsResult(
            int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        PermissionHelper.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    protected void onCameraStarted(SurfaceTexture surfaceTexture) {
        previewFrameTexture = surfaceTexture;
        // Make the display view visible to start showing the preview. This triggers the
        // SurfaceHolder.Callback added to (the holder of) previewDisplayView.
        previewDisplayView.setVisibility(View.VISIBLE);
    }

    protected Size cameraTargetResolution() {
        return null; // No preference and let the camera (helper) decide.
    }

    public void startCamera() {
        cameraHelper = new CameraXPreviewHelper();
        cameraHelper.setOnCameraStartedListener(
                surfaceTexture -> {
                    onCameraStarted(surfaceTexture);
                });
        CameraHelper.CameraFacing cameraFacing = CameraHelper.CameraFacing.FRONT;
        cameraHelper.startCamera(
                context, this, /*unusedSurfaceTexture=*/ cameraFacing, cameraTargetResolution());
    }

    protected Size computeViewSize(int width, int height) {
        return new Size(width, height);
    }

    protected void onPreviewDisplaySurfaceChanged(
            SurfaceHolder holder, int format, int width, int height) {
        // (Re-)Compute the ideal size of the camera-preview display (the area that the
        // camera-preview frames get rendered onto, potentially with scaling and rotation)
        // based on the size of the SurfaceView that contains the display.
        Size viewSize = computeViewSize(width, height);
        Size displaySize = cameraHelper.computeDisplaySizeFromViewSize(viewSize);
        boolean isCameraRotated = cameraHelper.isCameraRotated();

        // Connect the converter to the camera-preview frames as its input (via
        // previewFrameTexture), and configure the output width and height as the computed
        // display size.
        converter.setSurfaceTextureAndAttachToGLContext(
                previewFrameTexture,
                isCameraRotated ? displaySize.getHeight() : displaySize.getWidth(),
                isCameraRotated ? displaySize.getWidth() : displaySize.getHeight());
    }

    private void setupPreviewDisplayView(View view) {
        previewDisplayView.setVisibility(View.GONE);
        ViewGroup viewGroup = view.findViewById(R.id.preview_display_layout);
        viewGroup.addView(previewDisplayView);

        previewDisplayView
                .getHolder()
                .addCallback(
                        new SurfaceHolder.Callback() {
                            @Override
                            public void surfaceCreated(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(holder.getSurface());
                            }

                            @Override
                            public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
                                onPreviewDisplaySurfaceChanged(holder, format, width, height);
                            }

                            @Override
                            public void surfaceDestroyed(SurfaceHolder holder) {
                                processor.getVideoSurfaceOutput().setSurface(null);
                            }
                        });
    }

    public void receiveCountDownState(boolean isTimerRunning) {
        timerRunning = isTimerRunning;
    }

    private String getMultiHandLandmarksDebugString(List<NormalizedLandmarkList> multiHandLandmarks) {
        if (multiHandLandmarks.isEmpty()) {
            return "No hand landmarks";
        }
        String multiHandLandmarksStr = "";
        int handIndex = 0;
        for (NormalizedLandmarkList landmarks : multiHandLandmarks) {
            multiHandLandmarksStr +=
                    "\t#Hand landmarks for hand[" + handIndex + "]: " + landmarks.getLandmarkCount() + "\n";
            int landmarkIndex = 0;
            for (NormalizedLandmark landmark : landmarks.getLandmarkList()) {

                if (landmarkIndex == 8) {
                multiHandLandmarksStr +=
                        "\t\tLandmark ["
                                + landmarkIndex
                                + "]: ("
                                + landmark.getX()
                                + ", "
                                + landmark.getY()
                                + ", "
                                + landmark.getZ()
                                + ")\n";
                }
                ++landmarkIndex;
            }
            ++handIndex;
        }
        return multiHandLandmarksStr;
    }

    private String getAnglesOfFingersString(List<NormalizedLandmarkList> multiHandLandmarks) {
        if (multiHandLandmarks.isEmpty()) {
            return "No hand landmarks";
        }
        String fingerValuesString = null;
        int handIndex = 0;

        Vector3 palm0 = null;
        Vector3 palm5 = null;
        Vector3 palm17 = null;;

        Vector3 thumb1 = null;
        Vector3 thumb3 = null;
        Vector3 index5 = null;
        Vector3 index6 = null;
        Vector3 mid9 = null;
        Vector3 mid10 = null;
        Vector3 ring13 = null;
        Vector3 ring14 = null;

        for (NormalizedLandmarkList landmarks : multiHandLandmarks)  {
            int landmarkIndex = 0;
            for (NormalizedLandmark landmark : landmarks.getLandmarkList()) {
                if (landmarkIndex == 0) {
                    palm0 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 1) {
                    thumb1 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 3) {
                    thumb3 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 5) {
                    palm5 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 5) {
                    index5 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 6) {
                    index6 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 9) {
                    mid9 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 10) {
                    mid10 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 13) {
                    ring13 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 14) {
                    ring14 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                if (landmarkIndex == 17) {
                    palm17 = Vector3.of(landmark.getX(), landmark.getY(), landmark.getZ());
                }

                ++landmarkIndex;
            }

            Vector3 PalmNormal = calcPalmNormal(palm0, palm5, palm17);

            double thumbAngle = servoAngle(fingerDir(palm0, thumb3), PalmNormal, true);
            double indexAngle = servoAngle(fingerDir(index5, index6), PalmNormal, false);
            double midAngle = servoAngle(fingerDir(mid9, mid10), PalmNormal, false);
            double ringAngle = servoAngle(fingerDir(ring13, ring14), PalmNormal, false);

            fingerValuesString = (int)thumbAngle + "," + (int)indexAngle + "," + (int)midAngle + "," + (int)ringAngle;
            ++handIndex;
        }
        return fingerValuesString;
    }

    private Vector3 fingerDir(Vector3 startingPoint, Vector3 terminalPoint) {
        terminalPoint.sub(startingPoint);
        Vector3 direction = new Vector3(terminalPoint);
        direction.toNormal();
        return direction;
    }

    private Vector3 calcPalmNormal(Vector3 palm0, Vector3 palm5, Vector3 palm17) {
        palm5.sub(palm0);
        Vector3 side1 = palm5;

        palm17.sub(palm0);
        Vector3 side2 = palm17;

        side1.crossProduct(side2);

        Vector3 palmNormal = new Vector3(side1.toNormal());
        return palmNormal;
    }

    private double servoAngle(Vector3 fingerDir, Vector3 palmNormal, boolean isThumb) {
        //angle calculation by:
        //https://www.instructables.com/Robotic-Hand-controlled-by-Gesture-with-Arduino-Le/
        double scalarProduct = palmNormal.x * fingerDir.x + palmNormal.y * fingerDir.y + palmNormal.z * fingerDir.z;
        double palm_module = Math.sqrt(palmNormal.x * palmNormal.x + palmNormal.y * palmNormal.y + palmNormal.z * palmNormal.z);
        double finger_module = Math.sqrt(fingerDir.x * fingerDir.x + fingerDir.y * fingerDir.y + fingerDir.z * fingerDir.z);
        double angle_radians = Math.acos(scalarProduct / (palm_module * finger_module));
        double angle_degrees = angle_radians * 180 / Math.PI;

        double servoAngle;
        if (!isThumb) {
             servoAngle = (160 - (100 - angle_degrees) * 1.5); // EMPIRICAL CONVERSION, MAY BE DIFFERENT FOR DIFFERENT SERVOS!
        } else {
            servoAngle = (20+(100-angle_degrees)*1.5);; // EMPIRICAL CONVERSION, MAY BE DIFFERENT FOR DIFFERENT SERVOS
        }

        if(servoAngle < 1)
            servoAngle = 1;
        else if (servoAngle > 180)
            servoAngle = 180;

        return servoAngle;
    }

    static double map(double value, double start1, double stop1, double start2, double stop2) {
        double mappedValue = (value - start1) / (stop1 - start1) * (stop2 - start2) + start2;
        return mappedValue;
    }
}