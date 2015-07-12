package eu.crushedpixel.replaymod.recording;

import com.google.common.hash.Hashing;
import com.google.common.io.Files;
import eu.crushedpixel.replaymod.ReplayMod;
import eu.crushedpixel.replaymod.holders.Keyframe;
import eu.crushedpixel.replaymod.holders.Marker;
import eu.crushedpixel.replaymod.holders.PacketData;
import eu.crushedpixel.replaymod.utils.ReplayFile;
import eu.crushedpixel.replaymod.utils.ReplayFileIO;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import net.minecraft.client.Minecraft;
import org.apache.commons.io.FileUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

public abstract class DataListener extends ChannelInboundHandlerAdapter {

    private static final Logger logger = LogManager.getLogger();

    protected File file;
    protected Long startTime = null;
    protected String name;
    protected String worldName;
    protected boolean serverWasPaused;
    protected long lastSentPacket = 0;
    protected boolean alive = true;
    protected DataWriter dataWriter;
    protected Set<String> players = new HashSet<String>();
    protected Set<Keyframe<Marker>> markers = new HashSet<Keyframe<Marker>>();
    private boolean singleplayer;

    private int saveState = 0; //0: Idle, 1: Saving, 2: Saved

    private final File tempResourcePacksFolder = Files.createTempDir();
    private final Map<Integer, String> requestToHash = new ConcurrentHashMap<Integer, String>();
    private final Map<String, File> resourcePacks = new HashMap<String, File>();

    public DataListener(File file, String name, String worldName, long startTime, boolean singleplayer) throws FileNotFoundException {
        this.file = file;
        this.startTime = startTime;
        this.name = name;
        this.worldName = worldName;
        this.singleplayer = singleplayer;

        FileOutputStream fos = new FileOutputStream(file);
        BufferedOutputStream bos = new BufferedOutputStream(fos);
        DataOutputStream out = new DataOutputStream(bos);
        dataWriter = new DataWriter(out);

        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    if(saveState == 0) {
                        System.out.println("Saving Replay File to prevent Corruption");
                        DataListener.this.channelInactive(null);
                    }
                } catch(Exception e) {
                    e.printStackTrace();
                }
            }
        }, "shutdown-hook-data-listener"));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        dataWriter.requestFinish(players, markers);
    }

    protected void recordResourcePack(File file, int requestId) {
        try {
            String hash = Hashing.sha1().hashBytes(Files.toByteArray(file)).toString();
            requestToHash.put(requestId, hash);
            synchronized (resourcePacks) {
                if (!resourcePacks.containsKey(hash)) {
                    File tempFile = new File(tempResourcePacksFolder, hash);
                    FileUtils.copyFile(file, tempFile);
                    resourcePacks.put(hash, tempFile);
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to save resource pack.", e);
        }
    }

    public class DataWriter {

        private boolean active = true;
        private long paused;

        private ConcurrentLinkedQueue<PacketData> queue = new ConcurrentLinkedQueue<PacketData>();
        private DataOutputStream stream;

        Thread outputThread = new Thread(new Runnable() {

            @Override
            public void run() {

                while(active) {
                    PacketData dataReciever = queue.poll();
                    if(dataReciever != null) {
                        //write the ByteBuf to the given OutputStream

                        byte[] array = dataReciever.getByteArray();

                        if(array != null) {
                            try {
                                ReplayFileIO.writePacket(dataReciever, stream);
                                stream.flush();
                            } catch(Exception e) {
                                e.printStackTrace();
                            }
                        }

                    } else {
                        try {
                            //let the Thread sleep for 1/4 second and queue up new Packets
                            Thread.sleep(250L);
                        } catch(InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }

                try {
                    stream.flush();
                    stream.close();
                } catch(Exception e) {
                    e.printStackTrace();
                }

            }
        }, "replaymod-packet-writer");

        public DataWriter(DataOutputStream stream) {
            this.stream = stream;
            outputThread.start();
        }

        public synchronized void writePacket(byte[] bytes) {
            long now = System.currentTimeMillis();
            if(startTime == null) {
                startTime = now;
            }

            if (serverWasPaused) {
                paused = now - startTime - lastSentPacket;
                serverWasPaused = false;
            }
            int timestamp = (int) (now - startTime - paused);
            lastSentPacket = timestamp;
            queue.add(new PacketData(bytes, timestamp));
        }

        public void requestFinish(Set<String> players, Set<Keyframe<Marker>> markers) {
            active = false;

            try {
                ReplayMod.replayFileAppender.startNewReplayFileWriting();
                saveState = 1;

                String mcversion = Minecraft.getMinecraft().getVersion();
                String[] split = mcversion.split("-");
                if(split.length > 0) {
                    mcversion = split[0];
                }

                String[] pl = players.toArray(new String[players.size()]);

                String generator = "ReplayMod v" + ReplayMod.getContainer().getVersion();

                ReplayMetaData metaData = new ReplayMetaData(singleplayer, worldName, generator, (int) lastSentPacket, startTime, pl, mcversion);

                File folder = ReplayFileIO.getReplayFolder();

                File archive = new File(folder, name + ReplayFile.ZIP_FILE_EXTENSION);
                ReplayFileIO.writeReplayFile(archive, file, metaData, markers, resourcePacks, requestToHash);

                FileUtils.forceDelete(file);
                FileUtils.deleteDirectory(tempResourcePacksFolder);

            } catch(Exception e) {
                e.printStackTrace();
            } finally {
                ReplayMod.replayFileAppender.replayFileWritingFinished();
                saveState = 2;
            }
        }

    }

}
