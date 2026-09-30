# Terminal SBS mínimo

A Activity principal permite a rotação normal: em landscape exibe dois painéis com pesos iguais; em portrait oculta o espelho e o terminal original ocupa toda a largura. A visibilidade é atualizada na inicialização e em `onConfigurationChanged()`, mantendo a mesma sessão ao girar. Em `app/src/main/res/layout/activity_termux.xml`, o conteúdo do DrawerLayout contém os dois painéis. A `TerminalView` esquerda é configurada por `TermuxActivity.setTermuxTerminalViewAndClients()`; a sessão continua sendo vinculada por `TermuxTerminalSessionActivityClient.setCurrentSession()`.

A `TerminalMirrorView` direita chama o renderizador da view esquerda diretamente. As duas imagens usam a mesma `TerminalSession`, emulador, fonte, rolagem, seleção e estado do cursor. Apenas a view esquerda dimensiona o PTY e mantém a conexão com o teclado. Eventos de toque na direita são encaminhados à esquerda em coordenadas locais. Cada desenho da esquerda invalida a direita, incluindo saída da sessão, rolagem, alterações de fonte e piscar do cursor.

Não há captura de tela, buffer de imagem intermediário nem processo adicional. A renderização compartilhada está em `TerminalView.renderTerminal(Canvas)`, ponto de extensão para transformações futuras de escala e offset.

## Validação no Android

1. Abrir o app na vertical e conferir um único terminal em toda a largura; girar para landscape e conferir dois painéis 50/50. Voltar à vertical e confirmar que a sessão continua sendo a mesma.
2. Digitar `echo $$; tty; stty size`: ambos os painéis devem apresentar a mesma saída e cursor. O tamanho deve corresponder a um painel, não à largura total da tela.
3. Digitar pelo teclado virtual e físico, usar teclas extras e tocar em cada metade para abrir o teclado. Cada tecla deve ser enviada uma única vez.
4. Produzir saída contínua e rolar o histórico em cada metade: ambas devem acompanhar a mesma posição.
5. Trocar/criar sessões pelo drawer e alterar tamanho/fonte: ambas devem acompanhar a sessão selecionada e a apresentação.
6. Ocultar/exibir o teclado e retornar ao app: conferir dimensões e cursor sincronizados.

Esta primeira versão duplica o conteúdo do terminal. Teclado Android, drawer, toolbar e handles/menu flutuante de seleção mantêm sua interface normal. Controles de escala, offset, espaço central e correção de lente ficam para a próxima etapa.
