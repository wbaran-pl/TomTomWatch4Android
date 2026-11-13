package net.studioblueplanet.ttbin;

import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActivityCyclingCadenceTest
{
    private Activity createActiveActivity()
    {
        Activity activity=new Activity(new TtbinHeader());
        activity.setTimeZoneDifference(0);
        activity.parseRecord(new byte[] {
            TtbinFileDefinition.TAG_STATUS,
            TtbinFileDefinition.STATUS_ACTIVE,
            TtbinFileDefinition.ACTIVITY_CYCLING,
            (byte)0xe8, 0x03, 0x00, 0x00
        });
        return activity;
    }

    private byte[] wheelSize(int size)
    {
        return ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN)
                .put(TtbinFileDefinition.TAG_WHEEL_SIZE).putInt(size).array();
    }

    private byte[] cadence(int wheelRevolutions, int wheelTime, int crankRevolutions, int crankTime)
    {
        return ByteBuffer.allocate(11).order(ByteOrder.LITTLE_ENDIAN)
                .put(TtbinFileDefinition.TAG_CYCLING_CADENCE)
                .putInt(wheelRevolutions).putShort((short)wheelTime)
                .putShort((short)crankRevolutions).putShort((short)crankTime).array();
    }

    private byte[] gps(int timestamp)
    {
        return ByteBuffer.allocate(28).order(ByteOrder.LITTLE_ENDIAN)
                .put(TtbinFileDefinition.TAG_GPS)
                .putInt(10_000_000).putInt(10_000_000)
                .putShort((short)0).putShort((short)0)
                .putInt(timestamp).putShort((short)0)
                .putFloat(0.0f).putFloat(0.0f).put((byte)0).array();
    }

    @Test
    public void parsesCadenceAndWheelSpeedAcrossCounterWrap()
    {
        Activity activity=createActiveActivity();
        activity.parseRecord(wheelSize(2100));
        activity.parseRecord(cadence(65534, 65530, 65534, 65530));
        activity.parseRecord(cadence(1, 994, 0, 994));
        activity.parseRecord(gps(1000));

        ActivityRecord record=activity.getRecords(0).get(0);
        assertEquals(60, record.getCycles());
        assertEquals(2100.0*2.0/999.0, record.getSpeed(), 0.0001);
    }

    @Test
    public void clearsStaleCadenceAndWheelSpeedAfterFiveGpsRecords()
    {
        Activity activity=createActiveActivity();
        activity.parseRecord(wheelSize(2100));
        activity.parseRecord(cadence(100, 1000, 20, 1000));
        activity.parseRecord(cadence(103, 2000, 21, 2000));

        for (int timestamp=1000; timestamp<1006; timestamp++)
        {
            activity.parseRecord(gps(timestamp));
        }

        ActivityRecord record=activity.getRecords(0).get(5);
        assertEquals(0, record.getCycles());
        assertEquals(0.0, record.getSpeed(), 0.0);
    }

    @Test
    public void exportsCyclingCadenceInGpxAndTcx()
    {
        Activity activity=createActiveActivity();
        activity.parseRecord(cadence(100, 1000, 20, 1000));
        activity.parseRecord(cadence(101, 2000, 21, 2000));
        activity.parseRecord(gps(1001));
        activity.parseRecord(new byte[] {
            TtbinFileDefinition.TAG_STATUS,
            TtbinFileDefinition.STATUS_PAUSED,
            TtbinFileDefinition.ACTIVITY_CYCLING,
            (byte)0xea, 0x03, 0x00, 0x00
        });

        GpxWriter gpxWriter=GpxWriter.getInstance();
        gpxWriter.setGpxExtensions(true, false);
        StringWriter gpxOutput=new StringWriter();
        gpxWriter.writeTrackToFile(gpxOutput, activity, "testapp");
        assertTrue(gpxOutput.toString().contains("<gpxtpx:cad>60</gpxtpx:cad>"));

        StringWriter tcxOutput=new StringWriter();
        TcxWriter.getInstance().writeTrackToFile(tcxOutput, activity, "testapp");
        assertTrue(tcxOutput.toString().contains("<Cadence>60</Cadence>"));
    }
}
