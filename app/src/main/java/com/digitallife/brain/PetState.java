package com.digitallife.brain;

/**
 * 生理状态：桌宠当前的情绪/身体状态，驱动姿态目标。
 */
public enum PetState {
    /** 正常发呆 */
    IDLE,
    /** 犯困：半闭眼、低头 */
    SLEEPY,
    /** 无聊：寻求注意力 */
    BORED,
    /** 高兴 */
    HAPPY,
    /** 被戳烦/生气 */
    ANNOYED
}
