package com.linkall.cdk.example.sink;

import com.linkall.cdk.config.SinkConfig;

public class ExampleConfig extends SinkConfig {
    private int num;

    public int getNum() {
        return num;
    }

    public void setNum(int num) {
        this.num = num;
    }
}
