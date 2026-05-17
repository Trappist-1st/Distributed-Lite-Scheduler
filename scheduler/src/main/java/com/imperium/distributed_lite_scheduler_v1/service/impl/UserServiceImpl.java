package com.imperium.distributed_lite_scheduler_v1.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.imperium.distributed_lite_scheduler_v1.mapper.UserMapper;
import com.imperium.distributed_lite_scheduler_v1.model.entity.User;
import com.imperium.distributed_lite_scheduler_v1.service.UserService;
import org.springframework.stereotype.Service;

@Service
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements UserService {
}
