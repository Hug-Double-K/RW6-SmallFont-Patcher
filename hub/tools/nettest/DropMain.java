import io.github.hugdoublek.rw6hub.WatchDrop;
public class DropMain { public static void main(String[] a) throws Exception { WatchDrop d = WatchDrop.get(); WatchDrop.dir = new java.io.File(a[0]); d.setSink(l -> System.out.println(l)); System.out.println("PORT " + d.port()); System.out.flush(); Thread.sleep(600000); } }
