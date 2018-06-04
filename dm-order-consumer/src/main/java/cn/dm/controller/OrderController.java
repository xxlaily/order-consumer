package cn.dm.controller;

import cn.dm.common.Dto;
import cn.dm.service.OrderService;
import cn.dm.vo.CreateOrderVo;
import cn.dm.vo.ManagementOrderVo;
import cn.dm.vo.QueryOrderVo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.util.List;
import java.util.Map;

/**
 * 订单页Controller
 */
@RestController
@RequestMapping(value = "/api/")
public class OrderController {

    @Autowired
    private OrderService orderService;

    /**
     * 下单
     *
     * @param orderVo
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/createOrder", method = RequestMethod.POST)
    @ResponseBody
    public Dto createOrder(@RequestBody CreateOrderVo orderVo) throws Exception {
        return orderService.createOrder(orderVo);
    }

    /**
     * 根据订单号拆查询订单
     *
     * @param param-orderNo
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/queryOrderByOrderNo", method = RequestMethod.POST)
    @ResponseBody
    public Dto<QueryOrderVo> queryOrderByOrderNo(@RequestBody Map<String, Object> param) throws Exception {
        return orderService.queryOrderByOrderNo((String) param.get("orderNo"));
    }

    /**
     * 监听订单支付状态
     *
     * @param param-orderNo
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/queryOrderState", method = RequestMethod.POST)
    @ResponseBody
    public Dto queryOrderState(@RequestBody Map<String, Object> param) throws Exception {
        return orderService.queryOrderState((String) param.get("orderNo"));
    }


    /**
     * 查询订单列表
     *
     * @param param-orderType
     * @param param-orderTime
     * @param param-keyword
     * @return
     * @throws Exception
     */
    @RequestMapping(value = "/queryOrderList", method = RequestMethod.POST)
    @ResponseBody
    public Dto<List<ManagementOrderVo>> queryOrderList(@RequestBody Map<String, Object> param) throws Exception {
        return orderService.queryOrderList((Integer) param.get("orderType"), (Integer) param.get("orderTime"), (String) param.get("keyword"));
    }

}
