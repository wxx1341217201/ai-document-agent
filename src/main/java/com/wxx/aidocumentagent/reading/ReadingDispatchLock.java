package com.wxx.aidocumentagent.reading;

import jakarta.persistence.*;

@Entity @Table(name = "reading_dispatch_lock")
public class ReadingDispatchLock {
    @Id int id;
    protected ReadingDispatchLock() { }
}
