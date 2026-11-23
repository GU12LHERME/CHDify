## CHDify 1.0.0

Primeira versão estável com o novo nome (antes: Conversor CHD). O pacote `com.conversorchd` foi mantido.

**Novo**
- Conversão nos dois sentidos: ISO, BIN/CUE e GDI → CHD; CHD → ISO, BIN/CUE ou GDI (Dreamcast). Saídas idênticas às do chdman oficial.
- Ferramentas CHD: inspecionar, verificar, recompactar com outro perfil e exportar as informações técnicas.
- Fila salva em disco: itens pendentes sobrevivem ao fechamento do app; itens interrompidos podem ser reiniciados.
- Cancelar só o item atual ou a fila inteira (também pela notificação).
- Excluir original após converter (opcional, desligado por padrão): só após verificação, nunca após falha ou cancelamento, nunca em arquivos compartilhados; confirmação por padrão.
- Histórico local das operações.
- Organização em pastas por console e playlists .m3u para jogos multidisco.
- Checagem de espaço livre antes de iniciar e tratamento de perda de acesso a SD/USB.
- Modos de CPU: Econômico, Equilibrado e Desempenho.
- Novo ícone, tela de abertura e identidade visual CHDify.

**Observação sobre a instalação**
Versões anteriores (Conversor CHD) foram assinadas com chaves temporárias do CI. Para instalar o CHDify 1.0.0 é preciso desinstalar a versão antiga uma vez. A partir da build assinada com a chave de release, as próximas versões instalam por cima.
