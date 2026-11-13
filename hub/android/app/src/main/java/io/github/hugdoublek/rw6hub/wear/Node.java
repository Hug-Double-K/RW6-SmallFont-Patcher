package io.github.hugdoublek.rw6hub.wear;

/** 연결된 워치 하나 (Mi Fitness SDK의 Node 대신). id = 워치 블루투스 주소. */
public final class Node {
    public final String id;
    public final String name;

    public Node(String id, String name) {
        this.id = id;
        this.name = name;
    }
}
