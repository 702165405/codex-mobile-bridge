"""Deliver automatic IP-block alerts through the existing notification worker."""
import time
from pathlib import Path

from .notifications import MAX_DELIVERY_ATTEMPTS, channels, delivery_error, publish, publish_bark, publish_pushplus, read_json, settings, write_json


class SecurityNotifications:
    def __init__(self, data_dir):
        self.data_dir = Path(data_dir)
        self.path = self.data_dir/'security-notifications.json'
        self.state = read_json(self.path, {})

    def scan(self):
        blocks = read_json(self.data_dir/'auth-sessions.json', {}).get('autoBlocks', {})
        events = {row['id']: row for row in blocks.values()}
        prior_state = self.state
        self.state = {key: value for key, value in self.state.items() if key in events}
        if self.state != prior_state:
            write_json(self.path, self.state)
        config = settings(self.data_dir)
        if not config['securityEnabled']:
            for identifier in events:
                self.state[identifier] = {'muted': True}
            if self.state != prior_state:
                write_json(self.path, self.state)
            return
        for identifier, event in events.items():
            record = self.state.setdefault(identifier, {})
            if record.get('muted'):
                continue
            for channel, target in channels(config).items():
                prior = record.get(target, {})
                if prior.get('delivered') or prior.get('attempts', 0) >= MAX_DELIVERY_ATTEMPTS or time.time() < prior.get('next', 0):
                    continue
                current = settings(self.data_dir)
                if not current['securityEnabled']:
                    record['muted'] = True
                    break
                if channels(current).get(channel) != target:
                    continue
                title = 'Codex Mobile Bridge · IP 已自动封禁'
                body = (f"{current.get('addressName') or 'Codex Mobile Bridge'}：IP {event['ip']} 连续输错密码 {event['attempts']} 次，已加入黑名单。"
                        '\n关联登录已撤销。请在电脑网关 App 的“登录设备 → 自动封禁 IP”中查看或解除。')
                try:
                    {'ntfy': publish, 'bark': publish_bark, 'pushplus': publish_pushplus}[channel](current, title, body)
                    record[target] = {'delivered': True, 'channel': channel, 'time': time.time()}
                except Exception as error:
                    record[target] = {'delivered': False, 'channel': channel, 'next': time.time()+30,
                                      'attempts': prior.get('attempts', 0) + 1,
                                      'error': delivery_error(self.data_dir, channel, target, error,
                                          '封禁通知发送失败，累计失败 3 次后停止重试。请检查通知服务和网络。')}
                write_json(self.path, self.state)
