package com.spin.kill.server.dto;/**
 * Created by Administrator on 2019/6/21.
 */

import com.spin.kill.server.entity.ItemKillSuccess;
import lombok.Data;

import java.io.Serializable;

/**
 * @Author:debug (SteadyJack)
 * @Date: 2019/6/21 22:02
 **/
//继承了ItemKillSuccess就不用写那些字段了
@Data
public class KillSuccessUserInfo extends ItemKillSuccess implements Serializable{

    private String userName;

    private String phone;

    private String email;

    private String itemName;

    @Override
    public String toString() {
        return super.toString()+"\nKillSuccessUserInfo{" +
                "userName='" + userName + '\'' +
                ", phone='" + phone + '\'' +
                ", email='" + email + '\'' +
                ", itemName='" + itemName + '\'' +
                '}';
    }
}