package com.vance.sink;

import com.linkall.vance.common.json.JsonMapper;
import com.linkall.vance.core.Sink;
import com.linkall.vance.core.http.HttpClient;
import com.linkall.vance.core.http.HttpServer;
import io.vertx.core.json.JsonObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.linkall.vance.core.Sink;

import java.util.concurrent.atomic.AtomicInteger;

public class MySink implements Sink {

    private static final Logger LOGGER = LoggerFactory.getLogger(MySink.class);
    private static final AtomicInteger eventNum = new AtomicInteger(0);

    @Override
    public void start(){
        HttpServer server = HttpServer.createHttpServer();
        server.ceHandler(event -> {
            int num = eventNum.addAndGet(1);
            LOGGER.info("receive a new event, in total: "+num);
            //System.out.println("receive a new event, in total: "+num);
            JsonObject js = JsonMapper.wrapCloudEvent(event);
            JsonObject data = js.getJsonObject("data");
            HttpClient.deliver(data);
            LOGGER.info(data.encodePrettily());
            //System.out.println(js.encodePrettily());
        });
        server.listen();
    }
}
