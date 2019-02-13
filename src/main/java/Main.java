
/*----------------------------------------------------------------------------*/
/* Copyright (c) 2018 FIRST. All Rights Reserved.                             */
/* Open Source Software - may be modified and shared by FRC teams. The code   */
/* must be accompanied by the FIRST BSD license file in the root directory of */
/* the project.                                                               */
/*----------------------------------------------------------------------------*/

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import edu.wpi.cscore.CvSink;
import edu.wpi.cscore.CvSource;
import edu.wpi.cscore.MjpegServer;
import edu.wpi.cscore.UsbCamera;
import edu.wpi.cscore.VideoMode;
import edu.wpi.cscore.VideoSink;
import edu.wpi.cscore.VideoSource;
import edu.wpi.first.cameraserver.CameraServer;
import edu.wpi.first.networktables.NetworkTable;
import edu.wpi.first.networktables.NetworkTableEntry;
import edu.wpi.first.networktables.NetworkTableInstance;
import edu.wpi.first.vision.VisionPipeline;
import edu.wpi.first.vision.VisionThread;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.imgproc.Imgproc;

/*
   JSON format:
   {
       "team": <team number>,
       "ntmode": <"client" or "server", "client" if unspecified>
       "cameras": [
           {
               "name": <camera name>
               "path": <path, e.g. "/dev/video0">
               "pixel format": <"MJPEG", "YUYV", etc>   // optional
               "width": <video mode width>              // optional
               "height": <video mode height>            // optional
               "fps": <video mode fps>                  // optional
               "brightness": <percentage brightness>    // optional
               "white balance": <"auto", "hold", value> // optional
               "exposure": <"auto", "hold", value>      // optional
               "properties": [                          // optional
                   {
                       "name": <property name>
                       "value": <property value>
                   }
               ],
               "stream": {                              // optional
                   "properties": [
                       {
                           "name": <stream property name>
                           "value": <stream property value>
                       }
                   ]
               }
           }
       ]
   }
 */

public final class Main {
  private static String configFile = "/boot/frc.json";
  protected static int CameraWidth;
  protected static int CameraHeight;
  protected static int CameraFps;
  //protected static int CamPixFormat;

  public static class CameraConfig {
    public String name;
    public String path;
    public JsonObject config;
    public JsonElement streamConfig;
  }

  public static int team;
  public static boolean server;
  public static List<CameraConfig> cameraConfigs = new ArrayList<>();

  private Main() {
  }

  /**
   * Report parse error.
   */
  public static void parseError(String str) {
    System.err.println("config error in '" + configFile + "': " + str);
  }

  /**
   * Read single camera configuration.
   */
  public static boolean readCameraConfig(JsonObject config) {
    CameraConfig cam = new CameraConfig();

    // name
    JsonElement nameElement = config.get("name");
    if (nameElement == null) {
      parseError("could not read camera name");
      return false;
    }
    cam.name = nameElement.getAsString();
    JsonElement CameraWidthElement = config.get("width");
    CameraWidth = CameraWidthElement.getAsInt();

    JsonElement CameraHeightElement = config.get("height");
    CameraHeight = CameraHeightElement.getAsInt();

    JsonElement CameraFPSElement = config.get("fps");
    CameraFps = CameraFPSElement.getAsInt();

    //JsonElement CameraPixFormatElement = config.get("pixel format");
    //CamPixFormat = CameraPixFormatElement.getAsInt();
    // path
    JsonElement pathElement = config.get("path");
    if (pathElement == null) {
      parseError("camera '" + cam.name + "': could not read path");
      return false;
    }
    cam.path = pathElement.getAsString();

    // stream properties
    cam.streamConfig = config.get("stream");

    cam.config = config;
    
    cameraConfigs.add(cam);
    return true;
  }

  /**
   * Read configuration file.
   */
  //@SuppressWarnings("PMD.CyclomaticComplexity")
  public static boolean readConfig() {
    // parse file
    JsonElement top;
    try {
      top = new JsonParser().parse(Files.newBufferedReader(Paths.get(configFile)));
    } catch (IOException ex) {
      System.err.println("could not open '" + configFile + "': " + ex);
      return false;
    }

    // top level must be an object
    if (!top.isJsonObject()) {
      parseError("must be JSON object");
      return false;
    }
    JsonObject obj = top.getAsJsonObject();

    // team number
    JsonElement teamElement = obj.get("team");
    if (teamElement == null) {
      parseError("could not read team number");
      return false;
    }
    team = teamElement.getAsInt();

    // ntmode (optional)
    if (obj.has("ntmode")) {
      String str = obj.get("ntmode").getAsString();
      if ("client".equalsIgnoreCase(str)) {
        server = false;
      } else if ("server".equalsIgnoreCase(str)) {
        server = true;
      } else {
        parseError("could not understand ntmode value '" + str + "'");
      }
    }

    // cameras
    JsonElement camerasElement = obj.get("cameras");
    if (camerasElement == null) {
      parseError("could not read cameras");
      return false;
    }
    JsonArray cameras = camerasElement.getAsJsonArray();
    for (JsonElement camera : cameras) {
      if (!readCameraConfig(camera.getAsJsonObject())) {
        return false;
      }
    }

    return true;
  }

  /**
   * Start running the camera.
   */

  static CameraServer inst ;
  public static VideoSource startCamera(CameraConfig config) {
    System.out.println("Starting camera '" + config.name + "' on " + config.path);
    inst = CameraServer.getInstance();
    UsbCamera camera = new UsbCamera(config.name, config.path);
    MjpegServer server = inst.startAutomaticCapture(camera);

    Gson gson = new GsonBuilder().create();

    camera.setConfigJson(gson.toJson(config.config));
    camera.setConnectionStrategy(VideoSource.ConnectionStrategy.kKeepOpen);

    if (config.streamConfig != null) {
      server.setConfigJson(gson.toJson(config.streamConfig));
    }

    return camera;
  }

  /**
   * Example pipeline.
   */
  public static class MyPipeline implements VisionPipeline {
    public int val;

    @Override
    public void process(Mat mat) {
      val += 1;
    }
  }


  private final Object imgLock = new Object();
  /**
   * Main.
   */
  public static void main(String... args) {
    if (args.length > 0) {
      configFile = args[0];
    }

    // read configuration
    if (!readConfig()) {
      return;
    }
    //Mat imageFrames = new Mat();
    // start NetworkTables
    NetworkTableInstance ntinst = NetworkTableInstance.getDefault();
    if (server) {
      System.out.println("Setting up NetworkTables server");
      ntinst.startServer();
    } else {
      System.out.println("Setting up NetworkTables client for team " + team);
      ntinst.startClientTeam(team);
    }

    //Network Table Posting
    NetworkTable table = ntinst.getTable("videoInfo");
    NetworkTableEntry distance;
    NetworkTableEntry targetDisplacement;
    NetworkTableEntry inputsToTheDriveX;
    targetDisplacement = table.getEntry("TargetDisplacement");
    distance = table.getEntry("Target Distance Width");
    inputsToTheDriveX = table.getEntry("Input to the Drive");
    NetworkTableEntry distanceHeight = table.getEntry("Target Distance Height");
    NetworkTableEntry AvgDistance = table.getEntry("Avg. Distance");
    NetworkTableEntry AvgDistanceInCm = table.getEntry("Avg. Distance in cm");

    targetDisplacement = table.getEntry("TargetDisplacement");
    distance = table.getEntry("Target Distance Width");
        

    // start cameras
    List<VideoSource> cameras = new ArrayList<>();
    for (CameraConfig cameraConfig : cameraConfigs) {
      cameras.add(startCamera(cameraConfig));
      System.out.println(CameraWidth);
    }

    
    // start image processing on camera 0 if present
    if (cameras.size() >= 1) {
      VisionThread visionThread = new VisionThread(cameras.get(0),
              new RedTapeThree(), pipeline -> {
        if (!pipeline.filterContoursOutput().isEmpty() & pipeline.filterContoursOutput().size()==2) {
          synchronized (imgLock) {
            centerX = this.centerX;
            Rect r = Imgproc.boundingRect(pipeline.filterContoursOutput().get(0));
            Rect r2 = Imgproc.boundingRect(pipeline.findContoursOutput().get(1));
            targetDisplacement.setValue(((r.x + (r.width / 2))-(CameraWidth)/2)+(r2.x + (r2.width / 2))-(CameraWidth)/2);
            inputsToTheDriveX.setValue(r.width-(CameraWidth/2)/(CameraWidth/2));//added New
            distance.setValue((10.15/12)*CameraWidth/(2*r.width*Math.tan(68.5/2)));
            distanceHeight.setValue((5.5/12)*CameraHeight/(2*r.height*Math.tan(68.5/2)));
            AvgDistance.setValue(((10.15/12)*CameraWidth/(2*r.width*Math.tan(68.5/2))+(5.5/12)*CameraHeight/(2*r.height*Math.tan(68.5)))/2);
            AvgDistanceInCm.setValue((((10.15/12)*CameraWidth/(2*r.width*Math.tan(68.5/2))+(5.5/12)*CameraHeight/(2*r.height*Math.tan(68.5)))/2)*30.4);
            SmartDashboard.putString("Found", "Rect Found");  
          }
        }else {
          SmartDashboard.putString("Found", "Not Found");
          targetDisplacement.setValue(0);
          distance.setValue(-1);
        }
      });
     visionThread.start();
    }

    // loop forever
    for (;;) {
      try {
        Thread.sleep(10000);
      } catch (InterruptedException ex) {
        return;
      }
    }
    
  }
}
