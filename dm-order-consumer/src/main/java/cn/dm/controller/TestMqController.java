package cn.dm.controller;

import cn.dm.common.Constants;
import cn.dm.common.Dto;
import cn.dm.common.RabbitMQUtils;
import cn.dm.pojo.DmItem;
import cn.dm.pojo.DmOrder;
import cn.dm.vo.CreateOrderVo;
import cn.dm.vo.DmItemMessageVo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping(value = "/api/v/")
public class TestMqController {

    private Logger logger = LoggerFactory.getLogger(TestMqController.class);

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 下单
     *
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/sendCreateOrder", method = RequestMethod.POST)
    @ResponseBody
    public void sendCreateOrder() throws Exception {
        logger.info("开始发送消息");
        DmItemMessageVo dmItemMessageVo = new DmItemMessageVo();
        dmItemMessageVo.setOrderNo("201806250603235ebb88");
        dmItemMessageVo.setTradeNo("8888888888888");
        rabbitTemplate.convertAndSend("topicExchange", "key.leon-test", dmItemMessageVo);
        logger.info("消息发送完成");
    }
}
