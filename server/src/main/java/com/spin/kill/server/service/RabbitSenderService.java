package com.spin.kill.server.service;

public interface RabbitSenderService {
    public void sendKillSuccessEmailMsg(String orderNo);
}
