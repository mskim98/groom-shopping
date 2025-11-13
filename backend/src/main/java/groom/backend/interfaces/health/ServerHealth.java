package groom.backend.interfaces.health;

import com.sun.management.OperatingSystemMXBean;
import lombok.Getter;

import java.io.File;

@Getter
public class ServerHealth {

    private final String serverStatus = "OK";

    private final double freeDiskGb;
    private final double totalDiskGb;

    private final double freeMemoryGb;
    private final double totalMemoryGb;

    private final double freeJvmMemoryMb;
    private final double totalJvmMemoryMb;

    private final boolean dbConnected;

    private static final double GB = Math.pow(1024, 3);
    private static final double MB = Math.pow(1024, 2);
    private static final int FLOATING_POINT = (int) Math.pow(10, 2);

    public ServerHealth(boolean dbConnected, File file, Runtime runtime, OperatingSystemMXBean osBean) {
        this.freeDiskGb = getByteFloatingPoint(file.getFreeSpace(), GB);
        this.totalDiskGb = getByteFloatingPoint(file.getTotalSpace(), GB);
        this.freeMemoryGb = getByteFloatingPoint(osBean.getFreeMemorySize(), GB);
        this.totalMemoryGb = getByteFloatingPoint(osBean.getTotalMemorySize(), GB);
        this.freeJvmMemoryMb = getByteFloatingPoint(runtime.freeMemory(), MB);
        this.totalJvmMemoryMb = getByteFloatingPoint(runtime.totalMemory(), MB);
        this.dbConnected = dbConnected;
    }

    // 단위에 맞는 값의 소수점 자리를 FLOATING_POINT 자릿수 만큼 반환
    private double getByteFloatingPoint(long number, double byteUnit) {
        return Math.floor(number / byteUnit * FLOATING_POINT) / FLOATING_POINT;
    }
}
