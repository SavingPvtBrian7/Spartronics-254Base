package com.spartronics4915.frc2018;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import com.spartronics4915.frc2018.auto.AutoModeExecuter;
import com.spartronics4915.frc2018.loops.Looper;
import com.spartronics4915.frc2018.loops.RobotStateEstimator;
import com.spartronics4915.frc2018.loops.VisionProcessor;
import com.spartronics4915.frc2018.paths.profiles.PathAdapter;
import com.spartronics4915.frc2018.subsystems.ConnectionMonitor;
import com.spartronics4915.frc2018.subsystems.Drive;
import com.spartronics4915.frc2018.subsystems.Example;
import com.spartronics4915.frc2018.subsystems.LED;
import com.spartronics4915.frc2018.subsystems.Superstructure;
import com.spartronics4915.lib.util.CANProbe;
import com.spartronics4915.lib.util.CheesyDriveHelper;
import com.spartronics4915.lib.util.CrashTracker;
import com.spartronics4915.lib.util.DelayedBoolean;
import com.spartronics4915.lib.util.DriveSignal;
import com.spartronics4915.lib.util.SmartDashboardUtil;
import com.spartronics4915.lib.util.math.RigidTransform2d;

import edu.wpi.first.wpilibj.AnalogInput;
import edu.wpi.first.wpilibj.DriverStation;
import edu.wpi.first.wpilibj.IterativeRobot;
import edu.wpi.first.wpilibj.Timer;
import edu.wpi.first.wpilibj.smartdashboard.SmartDashboard;

/**
 * The main robot class, which instantiates all robot parts and helper classes
 * and initializes all loops. Some classes
 * are already instantiated upon robot startup; for those classes, the robot
 * gets the instance as opposed to creating a
 * new object
 * 
 * After initializing all robot parts, the code sets up the autonomous and
 * teleoperated cycles and also code that runs
 * periodically inside both routines.
 * 
 * This is the nexus/converging point of the robot code and the best place to
 * start exploring.
 * 
 * The VM is configured to automatically run this class, and to call the
 * functions corresponding to each mode, as
 * described in the IterativeRobot documentation. If you change the name of this
 * class or the package after creating
 * this project, you must also update the manifest file in the resource
 * directory.
 */
public class Robot extends IterativeRobot
{

    // Get subsystem instances
    private Drive mDrive = Drive.getInstance();
    private Superstructure mSuperstructure = Superstructure.getInstance();
    private LED mLED = LED.getInstance();
    private Example mExample = Example.getInstance();
    private RobotState mRobotState = RobotState.getInstance();
    private AutoModeExecuter mAutoModeExecuter = null;

    // Create subsystem manager
    private final SubsystemManager mSubsystemManager = new SubsystemManager(
            Arrays.asList(Drive.getInstance(), Superstructure.getInstance(),
                    ConnectionMonitor.getInstance(), LED.getInstance(), Example.getInstance()));

    // Initialize other helper objects
    private CheesyDriveHelper mCheesyDriveHelper = new CheesyDriveHelper();
    private ControlBoardInterface mControlBoard = new XboxControlBoard();

    private Looper mEnabledLooper = new Looper();

    //    private VisionServer mVisionServer = VisionServer.getInstance();

    private AnalogInput mCheckLightButton = new AnalogInput(Constants.kLEDOnId);

    private DelayedBoolean mDelayedAimButton;

    public Robot()
    {
        System.out.println("Robot is constructing.");
        CrashTracker.logRobotConstruction();
    }

    public void zeroAllSensors()
    {
        mSubsystemManager.zeroSensors();
        mRobotState.reset(Timer.getFPGATimestamp(), new RigidTransform2d());
    }

    /**
     * This function is run when the robot is first started up and should be
     * used for any initialization code.
     */
    @Override
    public void robotInit()
    {
        // Version string and related information
        try (InputStream manifest = getClass().getClassLoader().getResourceAsStream("META-INF/MANIFEST.MF"))
        {
            // build a version string
            Attributes attributes = new Manifest(manifest).getMainAttributes();
            String buildStr = "by: " + attributes.getValue("Built-By") +
                    "  on: " + attributes.getValue("Built-At") +
                    "  (" + attributes.getValue("Code-Version") + ")";
            SmartDashboard.putString("Build", buildStr);

            System.out.println("=================================================");
            System.out.println(Instant.now().toString());
            System.out.println("Built " + buildStr);
            System.out.println("=================================================");

        }
        catch (IOException e)
        {
            SmartDashboard.putString("Build", "version not found!");
            System.out.println("Build version not found!");
            DriverStation.reportError(e.getMessage(), false /*
                                                             * no stack trace
                                                             * needed
                                                             */);
        }

        try
        {
            CrashTracker.logRobotInit();

            CANProbe cp = new CANProbe();
            ArrayList<String> canDevices = cp.Find();
            System.out.println("CANDevicesFound:\n" + canDevices);
            SmartDashboard.putString("CANBusStatus",
                    canDevices.size() == Constants.kNumCANDevices ? "OK" : ("" + canDevices.size() + "/" + Constants.kNumCANDevices));

            mSubsystemManager.registerEnabledLoops(mEnabledLooper);
            mEnabledLooper.register(VisionProcessor.getInstance());
            mEnabledLooper.register(RobotStateEstimator.getInstance());

            //            mVisionServer.addVisionUpdateReceiver(VisionProcessor.getInstance());

            AutoModeSelector.initAutoModeSelector();

            mDelayedAimButton = new DelayedBoolean(Timer.getFPGATimestamp(), 0.1);
            // Force an true update now to prevent robot from running at start.
            mDelayedAimButton.update(Timer.getFPGATimestamp(), true);

            // Pre calculate the paths we use for auto.
            PathAdapter.calculatePaths();

        }
        catch (Throwable t)
        {
            CrashTracker.logThrowableCrash(t);
            throw t;
        }
        zeroAllSensors();
    }

    /**
     * Initializes the robot for the beginning of autonomous mode (set
     * drivebase, intake and superstructure to correct
     * states). Then gets the correct auto mode from the AutoModeSelector
     * 
     * @see AutoModeSelector.java
     */
    @Override
    public void autonomousInit()
    {
        try
        {
            CrashTracker.logAutoInit();

            System.out.println("Auto start timestamp: " + Timer.getFPGATimestamp());

            if (mAutoModeExecuter != null)
            {
                mAutoModeExecuter.stop();
            }

            zeroAllSensors();
            mSuperstructure.setWantedState(Superstructure.WantedState.IDLE);

            mAutoModeExecuter = null;

            // Shift to high
            mDrive.setHighGear(true);
            mDrive.setBrakeMode(true);

            mEnabledLooper.start();
            mAutoModeExecuter = new AutoModeExecuter();
            mAutoModeExecuter.setAutoMode(AutoModeSelector.getSelectedAutoMode());
            mAutoModeExecuter.start();

        }
        catch (Throwable t)
        {
            CrashTracker.logThrowableCrash(t);
            throw t;
        }
    }

    /**
     * This function is called periodically during autonomous
     */
    @Override
    public void autonomousPeriodic()
    {
        allPeriodic();
    }

    /**
     * Initializes the robot for the beginning of teleop
     */
    @Override
    public void teleopInit()
    {
        try
        {
            CrashTracker.logTeleopInit();

            // Start loopers
            mEnabledLooper.start();
            mDrive.setOpenLoop(DriveSignal.NEUTRAL);
            mDrive.setBrakeMode(false);
            // Shift to high
            mDrive.setHighGear(true);
            zeroAllSensors();
        }
        catch (Throwable t)
        {
            CrashTracker.logThrowableCrash(t);
            throw t;
        }
    }

    /**
     * This function is called periodically during operator control.
     * 
     * The code uses state machines to ensure that no matter what buttons the
     * driver presses, the robot behaves in a
     * safe and consistent manner.
     * 
     * Based on driver input, the code sets a desired state for each subsystem.
     * Each subsystem will constantly compare
     * its desired and actual states and act to bring the two closer.
     */
    @Override
    public void teleopPeriodic()
    {
        try
        {
            double throttle = mControlBoard.getThrottle();
            double turn = mControlBoard.getTurn();
            mDrive.setOpenLoop(mCheesyDriveHelper.cheesyDrive(throttle, turn, mControlBoard.getQuickTurn(),
                    !mControlBoard.getLowGear()));
            boolean wantLowGear = mControlBoard.getLowGear();
            mDrive.setHighGear(!wantLowGear);

            if (mControlBoard.getBlinkLEDButton())
            {
                mLED.setWantedState(LED.WantedState.BLINK);
            }
            if (mControlBoard.getExample())
            {
                mExample.setOn();
            }

            allPeriodic();
        }
        catch (Throwable t)
        {
            CrashTracker.logThrowableCrash(t);
            throw t;
        }
    }

    @Override
    public void disabledInit()
    {
        try
        {
            CrashTracker.logDisabledInit();

            if (mAutoModeExecuter != null)
            {
                mAutoModeExecuter.stop();
            }
            mAutoModeExecuter = null;

            mEnabledLooper.stop();

            // Call stop on all our Subsystems.
            mSubsystemManager.stop();

            mDrive.setOpenLoop(DriveSignal.NEUTRAL);

            PathAdapter.calculatePaths();
        }
        catch (Throwable t)
        {
            CrashTracker.logThrowableCrash(t);
            throw t;
        }
    }

    @Override
    public void disabledPeriodic()
    {
        final double kVoltageThreshold = 0.15;
        if (mCheckLightButton.getAverageVoltage() < kVoltageThreshold)
        {
            mLED.setLEDOn();
        }
        else
        {
            mLED.setLEDOff();
        }

        zeroAllSensors();
        allPeriodic();
    }

    @Override
    public void testInit()
    {
        Timer.delay(0.5);

        boolean results = Drive.getInstance().checkSystem();
        // e.g. results &= Intake.getInstance().checkSystem();

        if (!results)
        {
            System.out.println("CHECK ABOVE OUTPUT SOME SYSTEMS FAILED!!!");
        }
        else
        {
            System.out.println("ALL SYSTEMS PASSED");
        }
    }

    @Override
    public void testPeriodic()
    {
    }

    /**
     * Helper function that is called in all periodic functions
     */
    public void allPeriodic()
    {
        mRobotState.outputToSmartDashboard();
        mSubsystemManager.outputToSmartDashboard();
        mSubsystemManager.writeToLog();
        mEnabledLooper.outputToSmartDashboard();

        ConnectionMonitor.getInstance().setLastPacketTime(Timer.getFPGATimestamp());
    }
}
